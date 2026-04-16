package server;

import comum.ProtocolMessages;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;

/**
 * Gere a comunicação TCP com um cliente individual.
 * Cada instância corre numa thread própria.
 */
public class GerenciadorCliente implements Runnable {

    private final Socket socket;
    private final int idJogador;
    private final CountDownLatch startLatch;

    private PrintWriter out;
    private BufferedReader in;
    private volatile boolean conectado = true;

    private volatile String chutePendente = null;
    private volatile GerenciadorDeJogo gerenciadorDeJogo;

    private final String tag;

    public GerenciadorCliente(Socket socket, int idJogador, CountDownLatch startLatch) {
        this.socket = socket;
        this.idJogador = idJogador;
        this.startLatch = startLatch;
        this.tag = "[J" + idJogador + "]";
    }

    @Override
    public void run() {
        try {
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            System.out.println(tag + " Ligado: " + socket.getInetAddress());
            startLatch.countDown();

            String linha;
            while (conectado && (linha = in.readLine()) != null) {
                String chute = ProtocolMessages.parseChute(linha);
                if (chute != null && gerenciadorDeJogo != null) {
                    tratarChute(chute);
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

    private void tratarChute(String chute) {
        System.out.println(tag + " CHUTE recebido: " + chute);
        this.chutePendente = chute;
    }

    public void setGerenciadorDeJogo(GerenciadorDeJogo gerenciadorDeJogo) {
        this.gerenciadorDeJogo = gerenciadorDeJogo;
    }

    public synchronized String obterELimparChute() {
        String c = chutePendente;
        chutePendente = null;
        return c;
    }

    public void enviarMensagem(String msg) {
        if (out != null) {
            out.println(msg);
        }
    }

    public void desconectar() {
        if (!conectado) {
            return;
        }

        conectado = false;

        try {
            if (!socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }

        if (gerenciadorDeJogo != null) {
            gerenciadorDeJogo.jogadorDesconectado(idJogador);
        }
    }

    public int getIdJogador() {
        return idJogador;
    }

    public boolean isConectado() {
        return conectado && !socket.isClosed();
    }
}