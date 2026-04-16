package server;

import comum.ProtocolMessages;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Gere a lógica completa de uma sessão de jogo.
 */
public class GerenciadorDeJogo implements Runnable {

    public static final int MAX_TENTATIVAS = 6;
    public static final long TEMPO_RODADA = 15_000;

    private final List<GerenciadorCliente> jogadores;
    private final EstadoJogo estado;
    private final Set<Integer> jogadoresAtivos;

    private int rondaAtual = 0;
    private volatile boolean jogoARodar = true;

    public GerenciadorDeJogo(List<GerenciadorCliente> jogadores) {
        this.jogadores = new CopyOnWriteArrayList<>(jogadores);

        this.jogadoresAtivos = Collections.synchronizedSet(
                new HashSet<>(jogadores.stream()
                        .map(GerenciadorCliente::getIdJogador)
                        .collect(Collectors.toSet()))
        );

        String palavra = BancoDePalavras.getPalavraAleatoria();
        System.out.println("[GerenciadorDeJogo] Palavra escolhida: " + palavra + " (" + palavra.length() + " letras)");

        this.estado = new EstadoJogo(palavra, MAX_TENTATIVAS);

        for (GerenciadorCliente jogador : jogadores) {
            jogador.setGerenciadorDeJogo(this);
        }
    }

    @Override
    public void run() {
        System.out.println("[GerenciadorDeJogo] Jogo iniciado com " + jogadores.size() + " jogadores.");

        for (GerenciadorCliente jogador : jogadores) {
            jogador.enviarMensagem(ProtocolMessages.bemVindo(jogador.getIdJogador(), jogadores.size()));
        }

        dormir(300);

        broadcast(ProtocolMessages.inicio(
                estado.getMascara(),
                estado.getTentativasRestantes(),
                TEMPO_RODADA
        ));

        dormir(500);

        while (jogoARodar && !estado.isFinalizado()) {
            rondaAtual++;

            if (jogadoresAtivos.isEmpty()) {
                System.out.println("[GerenciadorDeJogo] Sem jogadores ativos. A terminar.");
                jogoARodar = false;
                break;
            }

            broadcast(ProtocolMessages.rodada(
                    rondaAtual,
                    estado.getMascara(),
                    estado.getTentativasRestantes(),
                    estado.getLetrasUsadasStr().isEmpty() ? "-" : estado.getLetrasUsadasStr()
            ));

            System.out.println("[GerenciadorDeJogo] Ronda " + rondaAtual + " — à espera das jogadas...");
            dormir(TEMPO_RODADA);

            processarResultadosRonda();

            if (estado.isFinalizado()) {
                break;
            }

            dormir(300);
        }

        finalizarJogo();
        System.out.println("[GerenciadorDeJogo] Jogo terminado.");
    }

    private void processarResultadosRonda() {
        System.out.println("[GerenciadorDeJogo] A processar ronda " + rondaAtual);

        for (GerenciadorCliente jogador : jogadores) {
            if (!jogador.isConectado()) {
                continue;
            }

            String chute = jogador.obterELimparChute();

            if (chute == null || chute.isBlank()) {
                System.out.println("[GerenciadorDeJogo] J" + jogador.getIdJogador() + " não jogou nesta ronda.");
                continue;
            }

            System.out.println("[GerenciadorDeJogo] J" + jogador.getIdJogador() + " jogou: " + chute);

            if (!estado.isFinalizado()) {
                boolean correto = estado.processarChute(chute, jogador.getIdJogador());

                System.out.println("[GerenciadorDeJogo]   → " +
                        (correto ? "ACERTOU" : "ERROU") +
                        " | Máscara: " + estado.getMascara() +
                        " | Tentativas: " + estado.getTentativasRestantes());
            }
        }

        broadcast(ProtocolMessages.estado(
                estado.getMascara(),
                estado.getTentativasRestantes(),
                estado.getLetrasUsadasStr().isEmpty() ? "-" : estado.getLetrasUsadasStr()
        ));
    }

    private void finalizarJogo() {
        List<Integer> vencedores = estado.getVencedores();

        if (!vencedores.isEmpty()) {
            String idsVencedores = vencedores.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));

            broadcast(ProtocolMessages.fimVitoria(idsVencedores, estado.getPalavra()));
            System.out.println("[GerenciadorDeJogo] Vencedores: " + idsVencedores);
        } else {
            broadcast(ProtocolMessages.fimPerda(estado.getPalavra()));
            System.out.println("[GerenciadorDeJogo] Derrota. A palavra era: " + estado.getPalavra());
        }

        dormir(500);

        for (GerenciadorCliente jogador : jogadores) {
            jogador.desconectar();
        }
    }

    public synchronized void jogadorDesconectado(int idJogador) {
        boolean removido = jogadoresAtivos.remove(idJogador);

        if (removido) {
            System.out.println("[GerenciadorDeJogo] J" + idJogador + " desligou-se. Ativos: " + jogadoresAtivos.size());
        }

        if (jogadoresAtivos.isEmpty()) {
            estado.setFinalizado(true);
            jogoARodar = false;
        }
    }

    private void broadcast(String mensagem) {
        System.out.println("[GerenciadorDeJogo] BROADCAST: " + mensagem);

        for (GerenciadorCliente jogador : jogadores) {
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