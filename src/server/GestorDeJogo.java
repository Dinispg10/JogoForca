package server;

import comum.ProtocolMessages;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Orquestrador central da lógica de uma sessão de jogo.
 * Coordena o fluxo de rondas, a comunicação sincronizada com os clientes 
 * e a transição de estados até ao fim da partida.
 */
public class GestorDeJogo implements Runnable {

    /** Número máximo de tentativas (erros) permitidos por jogador. */
    public static final int MAX_TENTATIVAS = 6;
    
    /** Duração de cada ronda em milissegundos. */
    public static final long TEMPO_RODADA  = 15_000;

    private final List<GestorCliente> jogadores;
    private final EstadoJogo estado;
    private final Set<Integer> jogadoresAtivos;

    private int rondaAtual = 0;
    private volatile boolean jogoARodar = true;

    /**
     * Inicializa o gestor com o grupo de jogadores conectados.
     * @param jogadores Lista de handlers de clientes que participarão na sessão.
     */
    public GestorDeJogo(List<GestorCliente> jogadores) {
        this.jogadores = new CopyOnWriteArrayList<>(jogadores);

        // Conjunto sincronizado para monitorizar jogadores que permanecem ligados
        this.jogadoresAtivos = Collections.synchronizedSet(
                new HashSet<>(jogadores.stream()
                        .map(GestorCliente::getIdJogador)
                        .collect(Collectors.toSet()))
        );

        String palavra = BancoDePalavras.getPalavraAleatoria();
        System.out.println("[GestorDeJogo] Palavra escolhida: " + palavra + " (" + palavra.length() + " letras)");

        this.estado = new EstadoJogo(palavra, MAX_TENTATIVAS);

        for (GestorCliente jogador : jogadores) {
            jogador.setGestorDeJogo(this);
            estado.adicionarJogador(jogador.getIdJogador());
        }
    }

    @Override
    public void run() {
        System.out.println("[GestorDeJogo] Jogo iniciado com " + jogadores.size() + " jogadores.");

        // Notificação de boas-vindas
        for (GestorCliente jogador : jogadores) {
            jogador.enviarMensagem(ProtocolMessages.bemVindo(jogador.getIdJogador(), jogadores.size()));
        }

        dormir(300);

        // Enviar estado inicial personalizado (com as tentativas individuais de cada um)
        for (GestorCliente jogador : jogadores) {
            if (jogador.isConectado()) {
                jogador.enviarMensagem(ProtocolMessages.inicio(
                        estado.getMascara(),
                        estado.getTentativasRestantes(jogador.getIdJogador()),
                        TEMPO_RODADA
                ));
            }
        }

        dormir(500);

        // Loop principal do jogo: continua enquanto o estado não for finalizado ou houver jogadores
        while (jogoARodar && !estado.isFinalizado()) {
            rondaAtual++;

            if (jogadoresAtivos.isEmpty()) {
                System.out.println("[GestorDeJogo] Sem jogadores ativos. A terminar.");
                jogoARodar = false;
                break;
            }

            // Início da ronda: broadcast do estado atualizado para cada jogador
            for (GestorCliente jogador : jogadores) {
                if (jogador.isConectado()) {
                    jogador.enviarMensagem(ProtocolMessages.rodada(
                            rondaAtual,
                            estado.getMascara(),
                            estado.getTentativasRestantes(jogador.getIdJogador()),
                            estado.getLetrasUsadasStr().isEmpty() ? "-" : estado.getLetrasUsadasStr()
                    ));
                }
            }

            System.out.println("[GestorDeJogo] Ronda " + rondaAtual + " - à espera das jogadas...");
            
            // Pausa a thread do gestor pela duração da ronda (os clientes enviam jogadas assincronamente)
            dormir(TEMPO_RODADA);

            // Processamento das jogadas recolhidas durante o intervalo
            processarResultadosRonda();

            if (estado.isFinalizado()) {
                break;
            }

            // Salvaguarda para evitar jogos infinitos (máximo 10 rondas)
            if (rondaAtual >= 10) {
                System.out.println("[GestorDeJogo] Limite de 10 rondas atingido!");
                estado.setFinalizado(true);
                break;
            }

            dormir(300);
        }

        finalizarJogo();
        System.out.println("[GestorDeJogo] Jogo terminado.");
    }

    /**
     * Recolhe as jogadas de todos os clientes conectados e delega o processamento ao EstadoJogo.
     */
    private void processarResultadosRonda() {
        System.out.println("[GestorDeJogo] A processar ronda " + rondaAtual);

        Map<Integer, String> jogadas = new HashMap<>();
        for (GestorCliente jogador : jogadores) {
            if (jogador.isConectado()) {
                // Só processa jogada se o jogador ainda estiver vivo no jogo
                if (estado.getTentativasRestantes(jogador.getIdJogador()) > 0) {
                    String jogada = jogador.obterELimparJogada();
                    System.out.println("[GestorDeJogo] J" + jogador.getIdJogador() + " jogou: " + 
                            (jogada == null || jogada.isBlank() ? "nada (Timeout)" : jogada));
                    jogadas.put(jogador.getIdJogador(), jogada);
                } else {
                    // Limpa buffers de jogadores eliminados para manter a consistência
                    jogador.obterELimparJogada();
                }
            }
        }

        estado.processarJogadasDaRonda(jogadas);
        System.out.println("[GestorDeJogo]   -> Máscara atual: " + estado.getMascara());

        // Enviar atualização de estado personalizada pós-processamento
        for (GestorCliente jogador : jogadores) {
            if (jogador.isConectado()) {
                jogador.enviarMensagem(ProtocolMessages.estado(
                        estado.getMascara(),
                        estado.getTentativasRestantes(jogador.getIdJogador()),
                        estado.getLetrasUsadasStr().isEmpty() ? "-" : estado.getLetrasUsadasStr()
                ));
            }
        }
    }

    /**
     * Determina o desfecho final da partida e notifica todos os participantes.
     */
    private void finalizarJogo() {
        List<Integer> vencedores = estado.getVencedores();

        if (!vencedores.isEmpty()) {
            // Vitória por adivinhação da palavra ou letras
            String idsVencedores = vencedores.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));

            broadcast(ProtocolMessages.fimVitoria(idsVencedores, estado.getPalavra()));
            System.out.println("[GestorDeJogo] Vencedores: " + idsVencedores);
        } else {
            // Cenário de vitória por abandono dos restantes jogadores
            List<Integer> sobreviventes = new ArrayList<>();
            for (GestorCliente h : jogadores) {
                if (h.isConectado() && estado.getTentativasRestantes(h.getIdJogador()) > 0) {
                    sobreviventes.add(h.getIdJogador());
                }
            }

            if (!sobreviventes.isEmpty()) {
                String idsVencedores = sobreviventes.stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(","));
                broadcast(ProtocolMessages.fimVitoria(idsVencedores, estado.getPalavra()));
                System.out.println("[GestorDeJogo] Vencedores por abandono: " + idsVencedores);
            } else {
                // Derrota total de todos os jogadores
                broadcast(ProtocolMessages.fimPerda(estado.getPalavra()));
                System.out.println("[GestorDeJogo] Derrota total. A palavra era: " + estado.getPalavra());
            }
        }

        dormir(500);

        // Encerra as ligações de todos os clientes
        for (GestorCliente jogador : jogadores) {
            jogador.desconectar();
        }
    }

    /**
     * Callback invocado quando um cliente se desconecta.
     * @param idJogador O ID do jogador que saiu.
     */
    public synchronized void jogadorDesconectado(int idJogador) {
        boolean removido = jogadoresAtivos.remove(idJogador);

        if (removido) {
            System.out.println("[GestorDeJogo] J" + idJogador + " desligou-se. Ativos: " + jogadoresAtivos.size());
            
            if (jogoARodar && !estado.isFinalizado()) {
                // Marca como derrotado por abandono (requisito de robustez)
                estado.eliminarJogador(idJogador);

                // Se restarem menos de 2 jogadores, a sessão competitiva é encerrada
                if (jogadoresAtivos.size() < 2) {
                    System.out.println("[GestorDeJogo] Menos de 2 jogadores restantes. A terminar sessão por abandono.");
                    jogoARodar = false;
                } else {
                    System.out.println("[GestorDeJogo] O jogo continua com " + jogadoresAtivos.size() + " jogadores.");
                }
            }
        }

        if (jogadoresAtivos.isEmpty()) {
            estado.setFinalizado(true);
            jogoARodar = false;
        }
    }

    /**
     * Envia uma mensagem para todos os clientes ainda conectados.
     */
    private void broadcast(String mensagem) {
        for (GestorCliente jogador : jogadores) {
            if (jogador.isConectado()) {
                jogador.enviarMensagem(mensagem);
            }
        }
    }

    private void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
