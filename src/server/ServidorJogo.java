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
 * Servidor principal do Jogo da Forca.
 *
 * Fluxo de arranque:
 *  1. Aguarda indefinidamente pelo 1.º jogador (primeiroJogadorLatch)
 *  2. Quando o 1.º jogador liga, abre o lobby por LOBBY_TIMEOUT_MS (20s)
 *  3. Se ao fim do lobby houver >= MIN_JOGADORES → inicia o jogo
 *     Caso contrário → encerra o servidor
 */
public class ServidorJogo {

    private static final int PORTA = 12345;
    private static final int MAX_JOGADORES = 4;
    private static final int MIN_JOGADORES = 2;

    /** Tempo de espera do lobby APÓS o 1.º jogador ligar (enunciado: ~20s) */
    private static final long LOBBY_TIMEOUT_MS = 20_000;

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PORTA)) {
            System.out.println("=== Jogo da Forca - Servidor ===");
            System.out.println("À escuta na porta " + PORTA);
            System.out.println("Mín. jogadores: " + MIN_JOGADORES + " | Máx.: " + MAX_JOGADORES);
            System.out.println("Lobby abre " + (LOBBY_TIMEOUT_MS / 1000) + "s após o 1.º jogador ligar");
            System.out.println("================================");
            System.out.println("[Servidor] À espera do 1.º jogador...");

            List<GestorCliente> handlers =
                    Collections.synchronizedList(new ArrayList<>());

            AtomicBoolean jogoIniciado = new AtomicBoolean(false);

            // Latch 1: dispara quando o 1.º jogador liga → inicia o timer do lobby
            CountDownLatch primeiroJogadorLatch = new CountDownLatch(1);

            // Latch 2: dispara quando MIN_JOGADORES estão ligados
            CountDownLatch minJogadoresLatch = new CountDownLatch(MIN_JOGADORES);

            Thread starterThread = new Thread(() -> {
                try {
                    // Fase 1: aguardar o 1.º jogador (sem timeout)
                    primeiroJogadorLatch.await();
                    System.out.println("[Servidor] 1.º jogador ligado! Lobby aberto por "
                            + (LOBBY_TIMEOUT_MS / 1000) + "s...");

                    // Fase 2: aguardar MIN_JOGADORES com timeout de LOBBY_TIMEOUT_MS
                    boolean chegaram = minJogadoresLatch.await(LOBBY_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                    System.out.println("[Servidor] "
                            + (chegaram ? "Mínimo de jogadores atingido." : "Tempo de lobby esgotado.")
                            + " Total: " + handlers.size() + " jogador(es).");

                    if (handlers.size() < MIN_JOGADORES) {
                        System.out.println("[Servidor] Jogadores insuficientes. A encerrar servidor.");
                        serverSocket.close();
                        return;
                    }

                    jogoIniciado.set(true);

                    List<GestorCliente> jogadoresDoJogo;
                    synchronized (handlers) {
                        jogadoresDoJogo = new ArrayList<>(handlers);
                    }

                    System.out.println("[Servidor] A iniciar jogo com " + jogadoresDoJogo.size() + " jogadores.");

                    GestorDeJogo gerenciador = new GestorDeJogo(jogadoresDoJogo);
                    Thread gameThread = new Thread(gerenciador, "GestorDeJogo");
                    gameThread.start();
                    gameThread.join();

                    System.out.println("[Servidor] Sessão de jogo concluída.");
                    serverSocket.close();

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("[Servidor] Thread de arranque interrompida.");
                } catch (IOException e) {
                    System.out.println("[Servidor] Erro: " + e.getMessage());
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

                if (jogoIniciado.get() || handlers.size() >= MAX_JOGADORES) {
                    System.out.println("[Servidor] Ligação recusada (jogo em curso ou sala cheia): "
                            + clientSocket.getInetAddress());
                    try {
                        PrintWriter pw = new PrintWriter(
                                new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8), true);
                        pw.println(ProtocolMessages.CHEIO);
                        clientSocket.close();
                    } catch (IOException ignored) {
                    }
                    continue;
                }

                int idJogador = handlers.size() + 1;
                // Passa minJogadoresLatch: cada cliente faz countDown() ao ligar
                GestorCliente handler = new GestorCliente(clientSocket, idJogador, minJogadoresLatch);
                handlers.add(handler);

                // Quando o 1.º jogador liga, dispara o timer do lobby
                if (handlers.size() == 1) {
                    primeiroJogadorLatch.countDown();
                }

                Thread threadCliente = new Thread(handler, "GestorCliente-J" + idJogador);
                threadCliente.start();

                System.out.println("[Servidor] Jogador " + idJogador + " ligado. Total: " + handlers.size());
            }

            System.out.println("[Servidor] Servidor encerrado.");

        } catch (IOException e) {
            System.err.println("[Servidor] Não foi possível iniciar o servidor: " + e.getMessage());
        }
    }
}
