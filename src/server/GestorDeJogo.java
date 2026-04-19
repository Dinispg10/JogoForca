package server;

import comum.ProtocolMessages;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Gere a lógica completa de uma sessão de jogo.
 */
public class GestorDeJogo implements Runnable {

    public static final int MAX_TENTATIVAS = 6;
    public static final long TEMPO_RODADA  = 15_000;

    private final List<GestorCliente> jogadores;
    private final EstadoJogo estado;
    private final Set<Integer> jogadoresAtivos;

    private int rondaAtual = 0;
    private volatile boolean jogoARodar = true;

    public GestorDeJogo(List<GestorCliente> jogadores) {
        this.jogadores = new CopyOnWriteArrayList<>(jogadores);

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

        for (GestorCliente jogador : jogadores) {
            jogador.enviarMensagem(ProtocolMessages.bemVindo(jogador.getIdJogador(), jogadores.size()));
        }

        dormir(300);

        // Enviar estado inicial personalizado (tentativas individuais)
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

        while (jogoARodar && !estado.isFinalizado()) {
            rondaAtual++;

            if (jogadoresAtivos.isEmpty()) {
                System.out.println("[GestorDeJogo] Sem jogadores ativos. A terminar.");
                jogoARodar = false;
                break;
            }

            // Enviar mensagem de ronda personalizada (tentativas individuais)
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
            dormir(TEMPO_RODADA);

            processarResultadosRonda();

            if (estado.isFinalizado()) {
                break;
            }

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

    private void processarResultadosRonda() {
        System.out.println("[GestorDeJogo] A processar ronda " + rondaAtual);

        Map<Integer, String> jogadas = new HashMap<>();
        for (GestorCliente jogador : jogadores) {
            if (jogador.isConectado()) {
                String jogada = jogador.obterELimparJogada();
                System.out.println("[GestorDeJogo] J" + jogador.getIdJogador() + " jogou: " + (jogada == null || jogada.isBlank() ? "nada (Timeout)" : jogada));
                jogadas.put(jogador.getIdJogador(), jogada);
            }
        }

        estado.processarJogadasDaRonda(jogadas);
        System.out.println("[GestorDeJogo]   -> Máscara atual: " + estado.getMascara());

        // Enviar estado personalizado
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

    private void finalizarJogo() {
        List<Integer> vencedores = estado.getVencedores();
        
        // Verifica se a palavra tem underscores para saber se foi adivinhada ou se foi ganho por eliminacao
        boolean palavraAdivinhada = !estado.getMascara().contains("_");

        if (!vencedores.isEmpty()) {
            String idsVencedores = vencedores.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));

            broadcast(ProtocolMessages.fimVitoria(idsVencedores, estado.getPalavra()));
            System.out.println("[GestorDeJogo] Vencedores: " + idsVencedores);
        } else {
            broadcast(ProtocolMessages.fimPerda(estado.getPalavra()));
            System.out.println("[GestorDeJogo] Derrota total. A palavra era: " + estado.getPalavra());
        }

        dormir(500);

        for (GestorCliente jogador : jogadores) {
            jogador.desconectar();
        }
    }

    public synchronized void jogadorDesconectado(int idJogador) {
        boolean removido = jogadoresAtivos.remove(idJogador);

        if (removido) {
            System.out.println("[GestorDeJogo] J" + idJogador + " desligou-se. Ativos: " + jogadoresAtivos.size());
            
            // Requisito novo: se um jogador se desconecta a meio, ele perde
            // e o jogo acaba dando vitória aos outros.
            if (jogoARodar && !estado.isFinalizado()) {
                System.out.println("[GestorDeJogo] J" + idJogador + " abandonou o jogo! A terminar sessão.");
                estado.eliminarJogador(idJogador);
                jogoARodar = false;
            }
        }

        if (jogadoresAtivos.isEmpty()) {
            estado.setFinalizado(true);
            jogoARodar = false;
        }
    }

    private void broadcast(String mensagem) {
        System.out.println("[GestorDeJogo] BROADCAST FINAL: " + mensagem);

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
