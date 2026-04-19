package server;

import comum.ProtocolMessages;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ponto de entrada do servidor para o Jogo da Forca Multijogador.
 * Gere o ciclo de vida do servidor, incluindo a aceitação de ligações, 
 * gestão do lobby de espera e lançamento da sessão de jogo.
 *
 * <p>Arquitetura de Arranque:
 * <ol>
 *  <li>Aguarda indefinidamente pela ligação do primeiro jogador.</li>
 *  <li>Após a primeira ligação, inicia uma contagem decrescente (lobby) de 20 segundos.</li>
 *  <li>Se o quórum mínimo for atingido dentro do tempo, o jogo inicia.</li>
 *  <li>Caso contrário, o servidor encerra por falta de jogadores.</li>
 * </ol>
 * </p>
 */
public class ServidorJogo {

    private static final int PORTA = 12345;
    private static final int MAX_JOGADORES = 4;
    private static final int MIN_JOGADORES = 2;

    /** Tempo de espera do lobby após a ligação do primeiro jogador (em milissegundos). */
    private static final long LOBBY_TIMEOUT_MS = 20_000;

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PORTA)) {
            System.out.println("=== Jogo da Forca - Servidor ===");
            System.out.println("À escuta na porta " + PORTA);
            System.out.println("Mín. jogadores: " + MIN_JOGADORES + " | Máx.: " + MAX_JOGADORES);
            System.out.println("Lobby abre " + (LOBBY_TIMEOUT_MS / 1000) + "s após o 1.º jogador ligar");
            System.out.println("================================");

            List<GestorCliente> handlers = Collections.synchronizedList(new ArrayList<>());
            AtomicBoolean jogoIniciado = new AtomicBoolean(false);

            // Sincronização: Aguarda o primeiro jogador para disparar o timer do lobby
            CountDownLatch primeiroJogadorLatch = new CountDownLatch(1);

            // Sincronização: Monitoriza o preenchimento da sala ou o timeout do lobby
            CountDownLatch lobbyLatch = new CountDownLatch(MAX_JOGADORES);

            // Thread de controlo do arranque da sessão
            Thread starterThread = new Thread(() -> {
                try {
                    // Fase 1: Bloqueia até que pelo menos um jogador se ligue
                    primeiroJogadorLatch.await();
                    System.out.println("[Servidor] 1.º jogador ligado! Lobby aberto por "
                            + (LOBBY_TIMEOUT_MS / 1000) + "s...");

                    // Fase 2: Aguarda até a sala encher ou o tempo esgotar
                    boolean salaCheia = lobbyLatch.await(LOBBY_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                    System.out.println("[Servidor] "
                            + (salaCheia ? "Sala cheia (4 jogadores)." : "Tempo de lobby esgotado.")
                            + " Total: " + handlers.size() + " jogador(es).");

                    if (handlers.size() < MIN_JOGADORES) {
                        System.out.println("[Servidor] Jogadores insuficientes. A encerrar servidor.");
                        for (GestorCliente handler : handlers) {
                            handler.enviarMensagem(ProtocolMessages.lobbyTimeout());
                        }
                        Thread.sleep(500); // Margem para propagação da mensagem
                        serverSocket.close();
                        return;
                    }

                    jogoIniciado.set(true);

                    List<GestorCliente> jogadoresDoJogo = new ArrayList<>();
                    synchronized (handlers) {
                        for (GestorCliente h : handlers) {
                            if (h.isConectado()) {
                                jogadoresDoJogo.add(h);
                            }
                        }
                    }

                    if (jogadoresDoJogo.size() < MIN_JOGADORES) {
                        System.out.println("[Servidor] Jogadores ativos insuficientes (" + jogadoresDoJogo.size() + "). A encerrar.");
                        serverSocket.close();
                        return;
                    }

                    System.out.println("[Servidor] A iniciar jogo com " + jogadoresDoJogo.size() + " jogadores.");

                    // Delega a gestão da partida para o GestorDeJogo
                    GestorDeJogo gestor = new GestorDeJogo(jogadoresDoJogo);
                    Thread gameThread = new Thread(gestor, "GestorDeJogo");
                    gameThread.start();
                    gameThread.join(); // Aguarda o fim da partida

                    System.out.println("[Servidor] Sessão de jogo concluída.");
                    serverSocket.close();

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("[Servidor] Erro crítico na thread de arranque.");
                } catch (IOException e) {
                    System.err.println("[Servidor] Erro de rede: " + e.getMessage());
                }
            }, "StarterThread");

            starterThread.start();

            // Loop principal de aceitação de sockets
            while (!serverSocket.isClosed()) {
                Socket clientSocket;
                try {
                    clientSocket = serverSocket.accept();
                } catch (IOException e) {
                    break;
                }

                // Rejeita novas ligações se o jogo já começou ou a sala está cheia
                if (jogoIniciado.get() || handlers.size() >= MAX_JOGADORES) {
                    System.out.println("[Servidor] Ligação recusada (jogo em curso ou sala cheia): "
                            + clientSocket.getInetAddress());
                    try {
                        PrintWriter pw = new PrintWriter(
                                new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8), true);
                        pw.println(ProtocolMessages.CHEIO);
                        clientSocket.close();
                    } catch (IOException ignored) {}
                    continue;
                }

                int idJogador = handlers.size() + 1;
                GestorCliente handler = new GestorCliente(clientSocket, idJogador, lobbyLatch);
                handlers.add(handler);

                // Dispara o timer do lobby se for o primeiro jogador
                if (handlers.size() == 1) {
                    primeiroJogadorLatch.countDown();
                }

                Thread threadCliente = new Thread(handler, "GestorCliente-J" + idJogador);
                threadCliente.start();

                System.out.println("[Servidor] Jogador " + idJogador + " ligado. Total: " + handlers.size());
            }

            System.out.println("[Servidor] Servidor encerrado.");

        } catch (IOException e) {
            System.err.println("[Servidor] Falha ao iniciar na porta " + PORTA + ": " + e.getMessage());
        }
    }
}
