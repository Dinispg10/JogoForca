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
                while (!jogoFinalizado && (linha = entrada.readLine()) != null) {
                    try {
                        tratarMensagemServidor(linha);
                    } catch (Exception e) {
                        synchronized (LOCK_CONSOLA) {
                            System.err.println("[!] Erro ao processar mensagem do servidor: " + e.getMessage());
                        }
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
            } finally {
                jogoFinalizado = true;
                podeJogar = false;
                atualizarDisplay = false;

                // Fechar socket para interromper o stdin.readLine() se possível
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                
                // Aguardar tempo suficiente para a box de vitória/derrota ser totalmente impressa
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                }

                // Dar tempo para o flush chegar ao terminal antes de matar o processo
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
                
                // Forçar saída para garantir que o programa termina, mesmo com o main bloqueado em readLine
                System.exit(0);
            }
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
                        if (promptVisivel && !jogoFinalizado && podeJogar) {
                            System.out.print("\033[s"); // Save cursor
                            System.out.print("\033[10A"); // Move up 10 lines
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

            if (!jogada.matches("\\p{L}+")) {
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

        // Nota: A thread recetor chama System.exit(0) quando o jogo termina,
        // portanto este código não é executado. Mantém-se para clareza.
    }

    private static void mostrarPrompt() {
        // FIX BUG 2: loop interno redundante removido — podeJogar já é true quando esta
        // função é chamada (o loop principal em main() garante isso)
        synchronized (LOCK_CONSOLA) {
            if (!promptVisivel) {
                System.out.print("==========\nA sua jogada (letra ou palavra): ");
                System.out.flush();
                promptVisivel = true;
            }
        }
    }

    private static void tratarMensagemServidor(String linha) {
        // Desativar temporariamente o timer enquanto processamos e imprimimos a mensagem
        atualizarDisplay = false;

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
                System.out.println("Erros:        0/" + tentativas);
                System.out.println("Tempo/ronda:  " + tempoRondaSegundos + "s");
                System.out.println("===========================");

            } else if (partes[0].equals(ProtocolMessages.RODADA)) {
                podeJogar = true;

                rondaAtual = Integer.parseInt(partes[1]);
                palavraMascara = partes[2];
                tentativasAtual = Integer.parseInt(partes[3]);
                // FIX BUG 9: partes[4] tem as letras usadas por TODOS os jogadores
                letrasUsadasGlobais = partes.length > 4 ? partes[4] : "-";

                int erros = maxTentativas - tentativasAtual;

                System.out.println();
                System.out.println("+----------------------------------------------+");
                System.out.println("| Ronda:        " + rondaAtual + "/10");
                System.out.println("| Palavra:      " + palavraMascara);
                System.out.println("| Erros:        " + erros + "/" + maxTentativas);
                System.out.println("| Letras (todos):  " + letrasUsadasGlobais);
                System.out.println("| Letras (minhas): " + formatarLetrasProprias());
                System.out.println("| Tempo:        " + desenharTimer(tempoRondaSegundos));
                System.out.println("+----------------------------------------------+");

                // Forca avança a cada erro
                System.out.println(desenharForca(erros));

                // Registar tempo inicial para o countdown APÓS imprimir tudo
                // para evitar que o timer dispare a meio da impressão
                inicioRonda = System.currentTimeMillis();
                atualizarDisplay = true;

                mostrarPrompt();

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

                boolean souVencedor = false;
                String[] ids = vencedoresIds.split(",");

                for (String id : ids) {
                    if (Integer.parseInt(id.trim()) == meuIdJogador) {
                        souVencedor = true;
                        break;
                    }
                }

                System.out.println();
                // Forca mostra o estado final (erros)
                int erros = maxTentativas - tentativasAtual;
                System.out.println(desenharForca(erros));
                System.out.println("+--------------------------------+");
                if (souVencedor) {
                    System.out.println("|         *** VITORIA! ***       |");
                } else {
                    System.out.println("|         *** DERROTA! ***       |");
                }
                System.out.println("|  Palavra: " + palavra + padding(palavra, 21) + "|");
                String displayVencedores = "P" + vencedoresIds.replace(",", ", P");
                System.out.println("|  Vencedor(es): " + displayVencedores + padding(displayVencedores, 15) + "|");
                System.out.println("|                                |");
                System.out.println("|   JOGO TERMINADO - ATÉ JÁ!     |");
                System.out.println("+--------------------------------+");

            } else if (partes[0].equals(ProtocolMessages.FIM_PERDA)) {
                jogoFinalizado = true;
                podeJogar = false;
                atualizarDisplay = false;

                String palavra = partes[1];

                System.out.println();
                System.out.println(desenharForca(maxTentativas));
                System.out.println("+--------------------------------+");
                System.out.println("|         *** DERROTA! ***       |");
                System.out.println("|  A palavra era: " + palavra + padding(palavra, 15) + "|");
                System.out.println("|                                |");
                System.out.println("|   JOGO TERMINADO - ATÉ JÁ!     |");
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

            } else if (linha.equals(ProtocolMessages.LOBBY_TIMEOUT)) {
                jogoFinalizado = true;
                podeJogar = false;
                atualizarDisplay = false;

                System.out.println();
                System.out.println("+--------------------------------+");
                System.out.println("|  Tempo de lobby expirado.      |");
                System.out.println("|  Jogadores insuficientes.      |");
                System.out.println("|  Servidor encerrado.           |");
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
        // FIX: Usa tempoRondaSegundos em vez de 15 fixo
        int preenchidos = (int) Math.min(totalBlocos, Math.max(0, segundos * totalBlocos / tempoRondaSegundos));

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < totalBlocos; i++) {
            sb.append(i < preenchidos ? "#" : "-");
        }
        sb.append("] ").append(segundos).append("s");
        return sb.toString();
    }

    private static String desenharForca(int erros) {
        boolean cabeca = erros >= 1;
        boolean tronco = erros >= 2;
        boolean bracoEsq = erros >= 3;
        boolean bracoDir = erros >= 4;
        boolean pernaEsq = erros >= 5;
        boolean pernaDir = erros >= 6;

        String l1 = "   +------+";                       
        String l2 = "   |      |";
        String l3 = cabeca ? "   |      O" : "   |";
        String l4 = bracoDir ? "   |     /|\\" : (bracoEsq ? "   |     /|" : (tronco ? "   |      |" : "   |"));
        String l5 = pernaDir ? "   |     / \\" : (pernaEsq ? "   |     /" : "   |");
        String l6 = "   |";
        String l7 = "==========";

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
