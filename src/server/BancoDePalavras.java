package server;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Gestor do repositório de palavras para o jogo.
 * Carrega palavras a partir de um ficheiro externo (words.txt) ou utiliza uma 
 * lista interna de recurso (fallback) caso o ficheiro não esteja disponível.
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
                System.out.println("[Servidor] Carregadas " + carregadas.size() + " palavras do ficheiro.");
            }
        } catch (IOException e) {
            System.err.println("[BancoDePalavras] Erro ao ler words.txt: " + e.getMessage());
        }

        // Fallback embutido caso o ficheiro externo falhe ou esteja vazio
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

    /** 
     * Seleciona e devolve uma palavra aleatória do repositório.
     * @return Uma String em maiúsculas representando a palavra escolhida.
     */
    public static String getPalavraAleatoria() {
        return PALAVRAS.get(ALEATORIO.nextInt(PALAVRAS.size()));
    }
}