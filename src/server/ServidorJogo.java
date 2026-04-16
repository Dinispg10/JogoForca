package server;

import comum.ProtocolMessages;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Servidor principal do Jogo da Forca.
 */
public class ServidorJogo {

    private static final int PORTA = 12345;
    private static final int MAX_JOGADORES = 4;
    private static final int MIN_JOGADORES = 2;
    private static final long TEMPO_TIMEOUT_INICIO_MS = 30_000;

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PORTA)) {
            System.out.println("=== Jogo da Forca - Servidor ===");
            System.out.println("À escuta na porta " + PORTA);
            System.out.println("Mín. jogadores: " + MIN_JOGADORES + " | Máx.: " + MAX_JOGADORES);
            System.out.println("Tempo limite para início: " + (TEMPO_TIMEOUT_INICIO_MS / 1000) + "s");
            System.out.println("================================");

            List<GerenciadorCliente> handlers =
                    Collections.synchronizedList(new ArrayList<>());

            CountDownLatch startLatch = new CountDownLatch(MIN_JOGADORES);
            final boolean[] jogoIniciado = {false};

            Thread starterThread = new Thread(() -> {
                try {
                    boolean minimoAtingido = startLatch.await(TEMPO_TIMEOUT_INICIO_MS, TimeUnit.MILLISECONDS);

                    if (!minimoAtingido) {
                        System.out.println("[Servidor] Tempo de espera esgotado. Apenas "
                                + handlers.size() + " jogador(es) ligados.");
                    }

                    if (handlers.size() < MIN_JOGADORES) {
                        System.out.println("[Servidor] Jogadores insuficientes para iniciar o jogo. A encerrar servidor.");
                        serverSocket.close();
                        return;
                    }

                    jogoIniciado[0] = true;

                    List<GerenciadorCliente> jogadoresDoJogo;
                    synchronized (handlers) {
                        jogadoresDoJogo = new ArrayList<>(handlers);
                    }

                    System.out.println("[Servidor] A iniciar jogo com " + jogadoresDoJogo.size() + " jogadores.");

                    GerenciadorDeJogo gerenciador = new GerenciadorDeJogo(jogadoresDoJogo);

                    Thread gameThread = new Thread(gerenciador, "GerenciadorDeJogo");
                    gameThread.start();
                    gameThread.join();

                    System.out.println("[Servidor] Sessão de jogo concluída.");
                    serverSocket.close();

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("[Servidor] Thread de arranque interrompida.");
                } catch (IOException e) {
                    System.out.println("[Servidor] Erro ao encerrar/iniciar: " + e.getMessage());
                }
            }, "StarterThread");

            starterThread.start();

            while (!serverSocket.isClosed()) {
                Socket clientSocket;
                try {
                    clientSocket = serverSocket.accept();
                } catch (IOException e) {
                    break;
                }

                if (jogoIniciado[0] || handlers.size() >= MAX_JOGADORES) {
                    System.out.println("[Servidor] Ligação recusada (jogo cheio ou já iniciado): "
                            + clientSocket.getInetAddress());

                    try {
                        PrintWriter pw = new PrintWriter(clientSocket.getOutputStream(), true);
                        pw.println(ProtocolMessages.CHEIO);
                        clientSocket.close();
                    } catch (IOException ignored) {
                    }

                    continue;
                }

                int idJogador = handlers.size() + 1;
                GerenciadorCliente handler = new GerenciadorCliente(clientSocket, idJogador, startLatch);
                handlers.add(handler);

                Thread threadCliente = new Thread(handler, "GerenciadorCliente-J" + idJogador);
                threadCliente.start();

                System.out.println("[Servidor] Jogador " + idJogador + " ligado. Total: " + handlers.size());
            }

            System.out.println("[Servidor] Servidor encerrado.");

        } catch (IOException e) {
            System.err.println("[Servidor] Não foi possível iniciar o servidor: " + e.getMessage());
        }
    }
}