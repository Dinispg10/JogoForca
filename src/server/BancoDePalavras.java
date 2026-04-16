package server;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Carrega palavras de words.txt (ou usa lista embutida como fallback)
 * e devolve uma palavra aleatória.
 */
public class BancoDePalavras {

    private static final List<String> PALAVRAS;

    static {
        List<String> carregadas = new ArrayList<>();
        // Tenta carregar do arquivo words.txt (relativo ao diretório de execução)
        try {
            Path caminho = Paths.get("words.txt");
            if (Files.exists(caminho)) {
                for (String linha : Files.readAllLines(caminho)) {
                    String palavra = linha.trim().toUpperCase();
                    if (!palavra.isEmpty()) {
                        carregadas.add(palavra);
                    }
                }
                System.out.println("[BancoDePalavras] Carregadas " + carregadas.size() + " palavras de words.txt");
            }
        } catch (IOException e) {
            System.err.println("[BancoDePalavras] Erro ao ler words.txt: " + e.getMessage());
        }

        // Fallback embutido
        if (carregadas.isEmpty()) {
            carregadas = Arrays.asList(
                "PROGRAMACAO", "DISTRIBUIDO", "SERVIDOR", "PROTOCOLO",
                "CONCORRENCIA", "SOCKET", "ALGORITMO", "SINCRONIZACAO"
            );
            System.out.println("[BancoDePalavras] A usar lista embutida de palavras.");
        }

        PALAVRAS = Collections.unmodifiableList(carregadas);
    }

    private static final Random ALEATORIO = new Random();

    /** Devolve uma palavra aleatória em maiúsculas. */
    public static String getPalavraAleatoria() {
        return PALAVRAS.get(ALEATORIO.nextInt(PALAVRAS.size()));
    }
}