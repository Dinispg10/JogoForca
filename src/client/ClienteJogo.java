package client;

import comum.ProtocolMessages;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Interface de utilizador e motor de comunicação para o Cliente do Jogo da Forca.
 * 
 * <p>Arquitetura Multi-Threaded:
 * <ul>
 *  <li><b>Thread Principal (main):</b> Gere a leitura do input do utilizador (stdin) e o envio de jogadas.</li>
 *  <li><b>Thread Recetora (Recetor):</b> Monitoriza o socket para mensagens do servidor em tempo real.</li>
 *  <li><b>Thread de Interface (TimerInterface):</b> Atualiza dinamicamente o countdown visual no terminal.</li>
 * </ul>
 * </p>
 * 
 * <p>Suporta sequências de escape ANSI para controlo do cursor e atualização dinâmica 
 * da consola sem flickering.</p>
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

    /** Letras submetidas especificamente por este cliente. */
    private static final Set<Character> letrasUsadasProprias = new LinkedHashSet<>();

    // Estado visual da ronda
    private static volatile int rondaAtual = 0;
    private static volatile long tempoRondaSegundos = 15;
    private static volatile long inicioRonda = 0;
    private static volatile String palavraMascara = "";
    private static volatile int tentativasAtual = 0;
    private static volatile boolean atualizarDisplay = false;

    /** Limite de tentativas configurado para a sessão atual. */
    private static volatile int maxTentativas = 10;

    /** Histórico global de letras utilizadas por todos os jogadores na sessão. */
    private static volatile String letrasUsadasGlobais = "-";

    /**
     * Ponto de entrada do cliente.
     * @param args Opcionalmente: [host] [porta]
     */
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

        // Utilização explícita de UTF-8 para garantir consistência de caracteres especiais entre SOs.
        PrintWriter saida = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        BufferedReader entrada = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));

        // Thread dedicada à receção de dados do servidor
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
                finalizarSessaoLocal(socket);
            }
        }, "Recetor");

        recetor.setDaemon(true);
        recetor.start();

        // Thread dedicada à atualização visual do timer no terminal via sequências ANSI
        Thread timerInterface = new Thread(() -> {
            while (!jogoFinalizado) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                
                if (podeJogar && atualizarDisplay && promptVisivel) {
                    long passados = (System.currentTimeMillis() - inicioRonda) / 1000;
                    long restantes = tempoRondaSegundos - passados;
                    if (restantes < 0) restantes = 0;
                    
                    synchronized (LOCK_CONSOLA) {
                        if (promptVisivel && !jogoFinalizado && podeJogar) {
                            System.out.print("\033[s"); // Guarda posição do cursor
                            System.out.print("\033[10A"); // Sobe 10 linhas para o campo do timer
                            System.out.print("\r\033[2K"); // Limpa a linha atual
                            System.out.print("| Tempo:        " + desenharTimer(restantes));
                            System.out.print("\033[u"); // Restaura cursor para o campo de input
                            System.out.flush();
                        }
                    }
                }
            }
        }, "TimerInterface");
        timerInterface.setDaemon(true);
        timerInterface.start();

        // Loop principal de leitura de input
        while (!jogoFinalizado) {
            if (!jogoIniciado || !podeJogar) {
                dormir(100);
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

            if (jogada == null) break;

            jogada = jogada.trim().toUpperCase();
            if (jogada.isEmpty()) continue;

            // Validação de input: apenas caracteres alfabéticos são permitidos
            if (!jogada.matches("\\p{L}+")) {
                synchronized (LOCK_CONSOLA) {
                    System.out.println("[!] Input inválido: utilize apenas letras.");
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
    }

    /**
     * Encerra os recursos locais do cliente e termina o processo.
     */
    private static void finalizarSessaoLocal(Socket socket) {
        jogoFinalizado = true;
        podeJogar = false;
        atualizarDisplay = false;

        try {
            socket.close();
        } catch (IOException ignored) {}
        
        // Pausa para visualização das mensagens finais antes do encerramento do processo
        dormir(2500);
        System.exit(0);
    }

    /**
     * Apresenta o prompt de input ao utilizador de forma sincronizada.
     */
    private static void mostrarPrompt() {
        synchronized (LOCK_CONSOLA) {
            if (!promptVisivel) {
                System.out.print("==========\nA sua jogada (letra ou palavra): ");
                System.out.flush();
                promptVisivel = true;
            }
        }
    }

    /**
     * Processa a lógica de receção de mensagens do servidor e atualiza a UI.
     * @param linha Mensagem bruta recebida do socket.
     */
    private static void tratarMensagemServidor(String linha) {
        atualizarDisplay = false; // Pausa o timer durante a impressão de novos dados
        String[] partes = linha.split(";");

        synchronized (LOCK_CONSOLA) {
            if (promptVisivel) {
                System.out.println();
                promptVisivel = false;
            }

            if (partes[0].equals(ProtocolMessages.BEM_VINDO)) {
                meuIdJogador = Integer.parseInt(partes[1]);
                totalJogadores = partes[2].equals("?") ? 0 : Integer.parseInt(partes[2]);
                imprimirBoasVindas();

            } else if (partes[0].equals(ProtocolMessages.INICIO)) {
                configurarJogoInicial(partes);

            } else if (partes[0].equals(ProtocolMessages.RODADA)) {
                processarNovaRonda(partes);

            } else if (partes[0].equals(ProtocolMessages.ESTADO)) {
                atualizarEstadoIntermedio(partes);

            } else if (partes[0].equals(ProtocolMessages.FIM_VITORIA)) {
                processarFimJogo(partes, true);

            } else if (partes[0].equals(ProtocolMessages.FIM_PERDA)) {
                processarFimJogo(partes, false);

            } else if (linha.equals(ProtocolMessages.CHEIO)) {
                System.out.println();
                System.out.println("+--------------------------------+");
                System.out.println("|  Servidor cheio ou jogo ja     |");
                System.out.println("|  em curso. Tente novamente     |");
                System.out.println("|  mais tarde.                   |");
                System.out.println("+--------------------------------+");
                jogoFinalizado = true;

            } else if (linha.equals(ProtocolMessages.LOBBY_TIMEOUT)) {
                System.out.println();
                System.out.println("+--------------------------------+");
                System.out.println("|  Tempo de lobby expirado.      |");
                System.out.println("|  Jogadores insuficientes.      |");
                System.out.println("|  Servidor encerrado.           |");
                System.out.println("+--------------------------------+");
                jogoFinalizado = true;

            } else {
                System.out.println("[Servidor] " + linha);
            }
        }
    }

    private static void imprimirBoasVindas() {
        System.out.println("\n+--------------------------------+");
        System.out.println("|      BEM-VINDO AO JOGO         |");
        System.out.printf("|   E o Jogador #%-2d             |%n", meuIdJogador);
        if (totalJogadores > 0) {
            System.out.printf("|   Total de jogadores: %-2d      |%n", totalJogadores);
        }
        System.out.println("+--------------------------------+");
    }

    private static void configurarJogoInicial(String[] partes) {
        jogoIniciado = true;
        letrasUsadasProprias.clear();
        letrasUsadasGlobais = "-";
        rondaAtual = 0;

        String mascara = partes[1];
        maxTentativas = Integer.parseInt(partes[2]);
        tempoRondaSegundos = Long.parseLong(partes[3]) / 1000;

        System.out.println("\n====== JOGO INICIADO ======");
        System.out.println("Palavra:      " + mascara);
        System.out.println("Erros:        0/" + maxTentativas);
        System.out.println("Tempo/ronda:  " + tempoRondaSegundos + "s");
        System.out.println("===========================");
    }

    private static void processarNovaRonda(String[] partes) {
        podeJogar = true;
        rondaAtual = Integer.parseInt(partes[1]);
        palavraMascara = partes[2];
        tentativasAtual = Integer.parseInt(partes[3]);
        letrasUsadasGlobais = partes.length > 4 ? partes[4] : "-";

        int erros = maxTentativas - tentativasAtual;

        System.out.println("\n+----------------------------------------------+");
        System.out.println("| Ronda:        " + rondaAtual + "/10");
        System.out.println("| Palavra:      " + palavraMascara);
        System.out.println("| Erros:        " + erros + "/" + maxTentativas);
        System.out.println("| Letras (todos):  " + letrasUsadasGlobais);
        System.out.println("| Letras (minhas): " + formatarLetrasProprias());
        System.out.println("| Tempo:        " + desenharTimer(tempoRondaSegundos));
        System.out.println("+----------------------------------------------+");
        System.out.println(desenharForca(erros));

        inicioRonda = System.currentTimeMillis();
        atualizarDisplay = true;
        mostrarPrompt();
    }

    private static void atualizarEstadoIntermedio(String[] partes) {
        podeJogar = false;
        atualizarDisplay = false;

        String mascara = partes[1];
        tentativasAtual = Integer.parseInt(partes[2]);
        letrasUsadasGlobais = partes.length > 3 ? partes[3] : "-";

        System.out.println("\n> Estado: " + mascara +
                " | Tentativas: " + tentativasAtual +
                " | Usadas (todos): " + letrasUsadasGlobais +
                " | Minhas: " + formatarLetrasProprias());
    }

    private static void processarFimJogo(String[] partes, boolean vitoria) {
        jogoFinalizado = true;
        podeJogar = false;
        atualizarDisplay = false;

        System.out.println("\n" + desenharForca(maxTentativas - tentativasAtual));
        System.out.println("+--------------------------------+");
        
        if (vitoria) {
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

            if (souVencedor) {
                System.out.println("|         *** VITORIA! ***       |");
            } else {
                System.out.println("|         *** DERROTA! ***       |");
            }
            System.out.println("|  Palavra: " + palavra + padding(palavra, 21) + "|");
            String displayVencedores = "P" + vencedoresIds.replace(",", ", P");
            System.out.println("|  Vencedor(es): " + displayVencedores + padding(displayVencedores, 15) + "|");
        } else {
            String palavra = partes[1];
            System.out.println("|         *** DERROTA! ***       |");
            System.out.println("|  A palavra era: " + palavra + padding(palavra, 15) + "|");
        }
        
        System.out.println("|                                |");
        System.out.println("|   JOGO TERMINADO - ATÉ JÁ!     |");
        System.out.println("+--------------------------------+");
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
        if (letrasUsadasProprias.isEmpty()) return "(nenhuma)";
        return letrasUsadasProprias.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    /**
     * Desenha uma barra de progresso visual para o timer.
     */
    private static String desenharTimer(long segundos) {
        int totalBlocos = 10;
        int preenchidos = (int) Math.min(totalBlocos, Math.max(0, segundos * totalBlocos / tempoRondaSegundos));

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < totalBlocos; i++) {
            sb.append(i < preenchidos ? "#" : "-");
        }
        sb.append("] ").append(segundos).append("s");
        return sb.toString();
    }

    /**
     * Representação ASCII da forca baseada no número de erros.
     */
    private static String desenharForca(int erros) {
        // Normaliza para o máximo de 6 estágios visuais da forca clássica
        int estagio = (int) Math.round(erros * 6.0 / maxTentativas);
        
        boolean cabeca = estagio >= 1;
        boolean tronco = estagio >= 2;
        boolean bracoEsq = estagio >= 3;
        boolean bracoDir = estagio >= 4;
        boolean pernaEsq = estagio >= 5;
        boolean pernaDir = estagio >= 6;

        String l1 = "   +------+";                       
        String l2 = "   |      |";
        String l3 = cabeca ? "   |      O" : "   |";
        String l4 = bracoDir ? "   |     /|\\" : (bracoEsq ? "   |     /|" : (tronco ? "   |      |" : "   |"));
        String l5 = pernaDir ? "   |     / \\" : (pernaEsq ? "   |     /" : "   |");
        String l6 = "   |";
        String l7 = "==========";

        return l1 + "\n" + l2 + "\n" + l3 + "\n" + l4 + "\n" + l5 + "\n" + l6 + "\n" + l7;
    }

    private static String padding(String texto, int larguraTotal) {
        int espacos = larguraTotal - texto.length();
        return espacos > 0 ? " ".repeat(espacos) : "";
    }

    private static void dormir(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
