package server;

import comum.ProtocolMessages;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

/**
 * Responsável pela gestão da comunicação TCP individual com cada cliente.
 * Cada instância desta classe é executada numa thread dedicada, permitindo 
 * a receção assíncrona de jogadas.
 *
 * <p>Implementa o timeout de ronda utilizando {@code socket.setSoTimeout()}, 
 * conforme os requisitos técnicos do projeto.</p>
 */
public class GestorCliente implements Runnable {

    private final Socket socket;
    private final int idJogador;
    private final CountDownLatch latch;

    private PrintWriter out;
    private BufferedReader in;
    private volatile boolean conectado = true;

    private String jogadaPendente = null;
    private volatile GestorDeJogo gestorDeJogo;

    private final String tag;

    /**
     * Construtor do gestor de cliente.
     * @param socket O socket da ligação estabelecida.
     * @param idJogador O ID atribuído a este jogador.
     * @param latch Latch de sincronização para sinalizar que o cliente está pronto.
     */
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

            // Configuração do timeout do socket para a leitura das jogadas.
            // Se o jogador não enviar dados dentro do tempo limite da ronda, 
            // readLine() lança uma SocketTimeoutException.
            socket.setSoTimeout((int) gestorDeJogo.TEMPO_RODADA);

            System.out.println(tag + " Ligado: " + socket.getInetAddress());

            // Sinaliza ao servidor que este jogador concluiu o aperto de mão inicial
            latch.countDown();

            while (conectado) {
                String linha;
                try {
                    linha = in.readLine();
                } catch (SocketTimeoutException e) {
                    // Timeout atingido: o jogador não respondeu a tempo nesta ronda.
                    // A ligação é mantida e o gestor de jogo tratará a ausência de jogada.
                    continue;
                }

                if (linha == null) break; // Ligação encerrada pelo lado do cliente

                String jogada = ProtocolMessages.parseJogada(linha);
                if (jogada != null && gestorDeJogo != null) {
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

    /**
     * Regista a jogada recebida do cliente para ser processada pelo gestor de jogo.
     */
    private synchronized void tratarChute(String jogada) {
        System.out.println(tag + " Jogada recebida: " + jogada);
        this.jogadaPendente = jogada;
    }

    public void setGestorDeJogo(GestorDeJogo gestorDeJogo) {
        this.gestorDeJogo = gestorDeJogo;
    }

    /**
     * Recupera a jogada pendente e limpa o buffer para a próxima ronda.
     * @return A jogada enviada ou null se não houve jogada.
     */
    public synchronized String obterELimparJogada() {
        String c = jogadaPendente;
        jogadaPendente = null;
        return c;
    }

    /**
     * Envia uma mensagem textual para o cliente.
     */
    public void enviarMensagem(String msg) {
        if (out != null) {
            out.println(msg);
        }
    }

    /**
     * Encerra a ligação com o cliente de forma segura.
     */
    public void desconectar() {
        if (!conectado) return;
        conectado = false;
        try {
            if (!socket.isClosed()) socket.close();
        } catch (IOException ignored) {
        }
        if (gestorDeJogo != null) {
            gestorDeJogo.jogadorDesconectado(idJogador);
        }
    }

    public int getIdJogador() { return idJogador; }

    public boolean isConectado() { return conectado && !socket.isClosed(); }
}

