package server;

import comum.ProtocolMessages;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

/**
 * Gere a comunicação TCP com um cliente individual.
 * Cada instância corre numa thread própria.
 *
 * Requisito técnico: usa socket.setSoTimeout() para implementar
 * o timeout por ronda conforme exigido pelo enunciado.
 */
public class GestorCliente implements Runnable {

    private final Socket socket;
    private final int idJogador;
    private final CountDownLatch latch;

    private PrintWriter out;
    private BufferedReader in;
    private volatile boolean conectado = true;

    private String jogadaPendente = null;
    private volatile GestorDeJogo GestorDeJogo;

    private final String tag;

    public GestorCliente(Socket socket, int idJogador, CountDownLatch latch) {
        this.socket = socket;
        this.idJogador = idJogador;
        this.latch = latch;
        this.tag = "[J" + idJogador + "]";
    }

    @Override
    public void run() {
        try {
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            // Requisito técnico do enunciado: socket.setSoTimeout() para timeout por ronda.
            // Se o jogador não enviar nada dentro de TEMPO_RODADA ms, readLine() lança
            // SocketTimeoutException — tratado abaixo sem desligar o cliente.
            socket.setSoTimeout((int) GestorDeJogo.TEMPO_RODADA);

            System.out.println(tag + " Ligado: " + socket.getInetAddress());

            // Sinaliza ao servidor que este jogador está pronto
            latch.countDown();

            while (conectado) {
                String linha;
                try {
                    linha = in.readLine();
                } catch (SocketTimeoutException e) {
                    // Timeout da ronda: jogador não respondeu a tempo.
                    // Não desliga — o GestorDeJogo irá consumir a tentativa
                    // ao detetar que jogadaPendente == null.
                    continue;
                }

                if (linha == null) break; // ligação encerrada pelo cliente

                String jogada = ProtocolMessages.parseChute(linha);
                if (jogada != null && GestorDeJogo != null) {
                    tratarChute(jogada);
                }
            }

        } catch (IOException e) {
            if (conectado) {
                System.out.println(tag + " Desligado inesperadamente: " + e.getMessage());
            }
        } finally {
            desconectar();
        }
    }

    private synchronized void tratarChute(String jogada) {
        System.out.println(tag + " jogada recebido: " + jogada);
        this.jogadaPendente = jogada;
    }

    public void setGestorDeJogo(GestorDeJogo GestorDeJogo) {
        this.GestorDeJogo = GestorDeJogo;
    }

    public synchronized String obterELimparJogada() {
        String c = jogadaPendente;
        jogadaPendente = null;
        return c;
    }

    public void enviarMensagem(String msg) {
        if (out != null) {
            out.println(msg);
        }
    }

    public void desconectar() {
        if (!conectado) return;
        conectado = false;
        try {
            if (!socket.isClosed()) socket.close();
        } catch (IOException ignored) {
        }
        if (GestorDeJogo != null) {
            GestorDeJogo.jogadorDesconectado(idJogador);
        }
    }

    public int getIdJogador() { return idJogador; }

    public boolean isConectado() { return conectado && !socket.isClosed(); }
}
