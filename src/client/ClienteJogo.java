package client;

import comum.ProtocolMessages;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Cliente do Jogo da Forca.
 *
 * Arquitetura:
 *  - Thread principal: lê input do utilizador e envia jogadas
 *  - Thread recetora: lê mensagens do servidor e mostra no terminal
 */
public class ClienteJogo {

    private static final String HOST = "localhost";
    private static final int PORTA = 12345;

    private static int meuIdJogador = -1;
    private static int totalJogadores = 0;

    private static volatile boolean jogoIniciado = false;
    private static volatile boolean podeJogar = false;
    private static volatile boolean jogoFinalizado = false;

    private static final Object LOCK_CONSOLA = new Object();
    private static volatile boolean promptVisivel = false;

    // Guarda apenas as letras usadas por ESTE cliente
    private static final Set<Character> letrasUsadasProprias = new LinkedHashSet<>();

    // Informação visual da ronda
    private static volatile int rondaAtual = 0;
    private static volatile long tempoRondaSegundos = 15;
    private static volatile long inicioRonda = 0;
    private static volatile String palavraMascara = "";
    private static volatile int tentativasAtual = 0;
    private static volatile boolean atualizarDisplay = false;

    // FIX BUG 8: maxTentativas guardado do INICIO para calcular erros e barra corretamente
    private static volatile int maxTentativas = 10;

    // FIX BUG 9: letras usadas por TODOS os jogadores (recebidas do servidor)
    private static volatile String letrasUsadasGlobais = "-";

    // Número máximo de rondas para completar o desenho da forca
    private static final int MAX_PARTES_FORCA = 8;

    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : HOST;
        int porta = args.length > 1 ? Integer.parseInt(args[1]) : PORTA;

        synchronized (LOCK_CONSOLA) {
            System.out.println("=== Jogo da Forca ===");
            System.out.println("A ligar a " + host + ":" + porta + "...");
        }

        Socket socket;
        try {
            socket = new Socket(host, porta);
        } catch (IOException e) {
            synchronized (LOCK_CONSOLA) {
                System.err.println("Não foi possível ligar ao servidor: " + e.getMessage());
            }
            return;
        }

        synchronized (LOCK_CONSOLA) {
            System.out.println("Ligado! À espera do início do jogo...");
            System.out.println();
        }

        // FIX BUG 6: UTF-8 explícito para evitar problemas de codificação entre plataformas
        PrintWriter saida = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        BufferedReader entrada = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));

        Thread recetor = new Thread(() -> {
            try {
                String linha;
                while ((linha = entrada.readLine()) != null) {
                    tratarMensagemServidor(linha);
                    if (jogoFinalizado) {
                        break;
                    }
                }
            } catch (IOException e) {
                if (!jogoFinalizado) {
                    synchronized (LOCK_CONSOLA) {
                        if (promptVisivel) {
                            System.out.println();
                            promptVisivel = false;
                        }
                        System.out.println("[!] A ligação ao servidor foi encerrada.");
                    }
                }
            }
            jogoFinalizado = true;
        }, "Recetor");

        recetor.setDaemon(true);
        recetor.start();

        Thread timerInterface = new Thread(() -> {
            while (!jogoFinalizado) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                
                if (podeJogar && atualizarDisplay && promptVisivel) {
                    long passados = (System.currentTimeMillis() - inicioRonda) / 1000;
                    long restantes = tempoRondaSegundos - passados;
                    if (restantes < 0) restantes = 0;
                    
                    synchronized (LOCK_CONSOLA) {
                        if (promptVisivel) {
                            System.out.print("\033[s"); // Save cursor
                            System.out.print("\033[11A"); // Move up 11 lines
                            System.out.print("\r\033[2K"); // Carriage return & clear line
                            System.out.print("| Tempo:        " + desenharTimer(restantes));
                            System.out.print("\033[u"); // Restore cursor
                            System.out.flush();
                        }
                    }
                }
            }
        }, "TimerInterface");
        timerInterface.setDaemon(true);
        timerInterface.start();

        while (!jogoFinalizado) {
            if (!jogoIniciado || !podeJogar) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }

            mostrarPrompt();

            String jogada;
            try {
                jogada = stdin.readLine();
                synchronized (LOCK_CONSOLA) {
                    promptVisivel = false;
                }
            } catch (IOException e) {
                break;
            }

            if (jogada == null) {
                break;
            }

            jogada = jogada.trim().toUpperCase();

            if (jogada.isEmpty()) {
                continue;
            }

            if (!jogada.matches("[A-Z]+")) {
                synchronized (LOCK_CONSOLA) {
                    System.out.println("[!] Só são aceites letras (sem espaços, números ou símbolos).");
                }
                continue;
            }

            saida.println(ProtocolMessages.jogada(jogada));

            registarJogadaPropria(jogada);

            synchronized (LOCK_CONSOLA) {
                System.out.println("[Enviado] jogada " + jogada);
            }

            podeJogar = false;
        }

        socket.close();

        synchronized (LOCK_CONSOLA) {
            System.out.println();
            System.out.println("Jogo terminado. Até à próxima!");
        }
    }

    private static void mostrarPrompt() {
        // FIX BUG 2: loop interno redundante removido — podeJogar já é true quando esta
        // função é chamada (o loop principal em main() garante isso)
        synchronized (LOCK_CONSOLA) {
            System.out.print("==========\nA sua jogada (letra ou palavra): ");
            System.out.flush();
            promptVisivel = true;
        }
    }

    private static void tratarMensagemServidor(String linha) {
        String[] partes = linha.split(";");

        synchronized (LOCK_CONSOLA) {
            if (promptVisivel) {
                System.out.println();
                promptVisivel = false;
            }

            if (partes[0].equals(ProtocolMessages.BEM_VINDO)) {
                meuIdJogador = Integer.parseInt(partes[1]);
                totalJogadores = partes[2].equals("?") ? 0 : Integer.parseInt(partes[2]);

                System.out.println();
                System.out.println("+--------------------------------+");
                System.out.println("|      BEM-VINDO AO JOGO         |");
                System.out.printf("|   E o Jogador #%-2d             |%n", meuIdJogador);
                if (totalJogadores > 0) {
                    System.out.printf("|   Total de jogadores: %-2d      |%n", totalJogadores);
                }
                System.out.println("+--------------------------------+");

            } else if (partes[0].equals(ProtocolMessages.INICIO)) {
                jogoIniciado = true;
                letrasUsadasProprias.clear();
                letrasUsadasGlobais = "-";
                rondaAtual = 0;

                String mascara = partes[1];
                int tentativas = Integer.parseInt(partes[2]);
                tempoRondaSegundos = Long.parseLong(partes[3]) / 1000;

                // FIX BUG 8: guarda o máximo de tentativas para calcular erros e barra
                maxTentativas = tentativas;

                System.out.println();
                System.out.println("====== JOGO INICIADO ======");
                System.out.println("Palavra:      " + mascara);
                System.out.println("Tentativas:   " + tentativas);
                System.out.println("Tempo/ronda:  " + tempoRondaSegundos + "s");
                System.out.println("===========================");

            } else if (partes[0].equals(ProtocolMessages.RODADA)) {
                podeJogar = true;

                rondaAtual = Integer.parseInt(partes[1]);
                palavraMascara = partes[2];
                tentativasAtual = Integer.parseInt(partes[3]);
                // FIX BUG 9: partes[4] tem as letras usadas por TODOS os jogadores
                letrasUsadasGlobais = partes.length > 4 ? partes[4] : "-";

                // Registar tempo inicial para o countdown
                inicioRonda = System.currentTimeMillis();
                atualizarDisplay = true;

                System.out.println();
                System.out.println("+-------------- Ronda " + rondaAtual + " --------------+");
                System.out.println("| Palavra:      " + palavraMascara);
                // FIX BUG 8: barraTentativas usa maxTentativas como total
                System.out.println("| Tentativas:   " + barraTentativas(tentativasAtual) + " (" + tentativasAtual + " restantes)");
                // FIX BUG 9: mostra letras de todos os jogadores (do servidor) + as próprias
                System.out.println("| Letras (todos):  " + letrasUsadasGlobais);
                System.out.println("| Letras (minhas): " + formatarLetrasProprias());
                System.out.println("| Tempo:        " + desenharTimer(tempoRondaSegundos));
                System.out.println("| Progresso:    " + rondaAtual + "/" + MAX_PARTES_FORCA);
                System.out.println("+----------------------------------------------+");

                // Forca avança a cada ronda — design intencional
                System.out.println(desenharForca(rondaAtual));

            } else if (partes[0].equals(ProtocolMessages.ESTADO)) {
                podeJogar = false;
                atualizarDisplay = false;

                String mascara = partes[1];
                int tentativas = Integer.parseInt(partes[2]);
                // FIX BUG 9: partes[3] tem as letras usadas globais no ESTADO
                letrasUsadasGlobais = partes.length > 3 ? partes[3] : "-";
                tentativasAtual = tentativas;

                System.out.println();
                System.out.println("> Estado: " + mascara +
                        " | Tentativas: " + tentativas +
                        " | Usadas (todos): " + letrasUsadasGlobais +
                        " | Minhas: " + formatarLetrasProprias());

            } else if (partes[0].equals(ProtocolMessages.FIM_VITORIA)) {
                jogoFinalizado = true;
                podeJogar = false;
                atualizarDisplay = false;

                String vencedoresIds = partes[1];
                String palavra = partes[2];
                String motivo = partes.length > 3 ? partes[3] : "Fim de jogo";

                boolean souVencedor = false;
                String[] ids = vencedoresIds.split(",");

                for (String id : ids) {
                    if (Integer.parseInt(id.trim()) == meuIdJogador) {
                        souVencedor = true;
                        break;
                    }
                }

                System.out.println();
                // Forca mostra o estado final da ronda
                System.out.println(desenharForca(rondaAtual));
                System.out.println("+--------------------------------+");
                if (souVencedor) {
                    System.out.println("|         *** VITORIA! ***       |");
                } else {
                    System.out.println("|         *** DERROTA! ***       |");
                }
                System.out.println("|  Palavra: " + palavra + padding(palavra, 21) + "|");
                System.out.println("|  Vencedor(es): P" + vencedoresIds + padding("P" + vencedoresIds, 14) + "|");
                if (!"-".equals(motivo)) {
                    System.out.println("|  " + motivo + padding(motivo, 30) + "|");
                }
                System.out.println("+--------------------------------+");

            } else if (partes[0].equals(ProtocolMessages.FIM_PERDA)) {
                jogoFinalizado = true;
                podeJogar = false;
                atualizarDisplay = false;

                String palavra = partes[1];
                String motivo = partes.length > 2 ? partes[2] : "Fim de jogo";

                System.out.println();
                System.out.println(desenharForca(MAX_PARTES_FORCA));
                System.out.println("+--------------------------------+");
                System.out.println("|         *** DERROTA! ***       |");
                System.out.println("|  A palavra era: " + palavra + padding(palavra, 15) + "|");
                if (!"-".equals(motivo)) {
                    System.out.println("|  " + motivo + padding(motivo, 30) + "|");
                }
                System.out.println("+--------------------------------+");

            } else if (linha.equals(ProtocolMessages.CHEIO)) {
                jogoFinalizado = true;
                podeJogar = false;
                atualizarDisplay = false;

                System.out.println();
                System.out.println("+--------------------------------+");
                System.out.println("|  Servidor cheio ou jogo ja     |");
                System.out.println("|  em curso. Tente novamente     |");
                System.out.println("|  mais tarde.                   |");
                System.out.println("+--------------------------------+");

            } else {
                System.out.println("[Servidor] " + linha);
            }
        }
    }

    private static void registarJogadaPropria(String jogada) {
        if (jogada != null && jogada.length() == 1) {
            char c = Character.toUpperCase(jogada.charAt(0));
            if (Character.isLetter(c)) {
                letrasUsadasProprias.add(c);
            }
        }
    }

    private static String formatarLetrasProprias() {
        if (letrasUsadasProprias.isEmpty()) {
            return "(nenhuma)";
        }

        StringBuilder sb = new StringBuilder();
        for (char c : letrasUsadasProprias) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String desenharTimer(long segundos) {
        int totalBlocos = 10;
        int preenchidos = (int) Math.min(totalBlocos, Math.max(0, segundos * totalBlocos / 15));

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < totalBlocos; i++) {
            sb.append(i < preenchidos ? "#" : "-");
        }
        sb.append("] ").append(segundos).append("s");
        return sb.toString();
    }

    private static String desenharForca(int ronda) {
        int etapa = Math.min(Math.max(ronda, 0), MAX_PARTES_FORCA);

        boolean base = etapa >= 1;
        boolean poste = etapa >= 2;
        boolean topo = etapa >= 3;
        boolean corda = etapa >= 4;
        boolean cabeca = etapa >= 5;
        boolean tronco = etapa >= 6;
        boolean bracos = etapa >= 7;
        boolean pernas = etapa >= 8;

        String l1 = "   +------+";                       
        String l2 = "   |      " + (corda ? "|" : "");
        String l3 = poste ? (cabeca ? "   |      O" : "   |") : "          ";
        String l4 = poste ? (bracos ? "   |     /|\\" : (tronco ? "   |      |" : "   |")) : "          ";
        String l5 = poste ? (pernas ? "   |     / \\" : "   |") : "          ";
        String l6 = poste ? "   |" : "";
        String l7 = base ? "==========" : "";

        if (!topo) {
            l1 = "          ";
        }

        return l1 + "\n" +
               l2 + "\n" +
               l3 + "\n" +
               l4 + "\n" +
               l5 + "\n" +
               l6 + "\n" +
               l7;
    }

    // FIX BUG 8: barra usa maxTentativas (10) como total de blocos, não 6
    private static String barraTentativas(int tentativas) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < maxTentativas; i++) {
            sb.append(i < tentativas ? "#" : "-");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String padding(String texto, int larguraTotal) {
        int espacos = larguraTotal - texto.length();
        return espacos > 0 ? " ".repeat(espacos) : "";
    }
}
