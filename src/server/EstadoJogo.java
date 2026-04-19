package server;

import java.util.*;

public class EstadoJogo {

    private final String palavra;
    private final Set<Character> letrasUsadas;
    private final List<Integer> vencedores;

    private final Map<Integer, Integer> tentativasPorJogador;
    private final int maxTentativas;
    
    private boolean finalizado;

    public EstadoJogo(String palavra, int maxTentativas) {
        this.palavra = palavra.toUpperCase();
        this.maxTentativas = maxTentativas;
        this.tentativasPorJogador = new HashMap<>();
        this.letrasUsadas = new HashSet<>();
        this.vencedores = new ArrayList<>();
        this.finalizado = false;
    }

    public synchronized void adicionarJogador(int idJogador) {
        tentativasPorJogador.put(idJogador, maxTentativas);
    }

    public synchronized void processarJogadasDaRonda(Map<Integer, String> jogadas) {
        if (finalizado) return;

        Set<Character> letrasAAdicionar = new HashSet<>();
        List<Integer> acertaram = new ArrayList<>();
        List<Integer> erraram = new ArrayList<>();

        for (Map.Entry<Integer, String> entry : jogadas.entrySet()) {
            int id = entry.getKey();
            String jogada = entry.getValue();

            if (jogada == null || jogada.isBlank()) {
                erraram.add(id);
                continue;
            }

            jogada = jogada.trim().toUpperCase();

            if (jogada.length() == 1) {
                char letra = jogada.charAt(0);
                if (!Character.isLetter(letra)) {
                    erraram.add(id);
                    continue;
                }

                if (letrasUsadas.contains(letra)) {
                    erraram.add(id);
                } else {
                    letrasAAdicionar.add(letra);
                    if (palavra.indexOf(letra) >= 0) {
                        acertaram.add(id);
                    } else {
                        erraram.add(id);
                    }
                }
            } else {
                if (jogada.equals(palavra)) {
                    acertaram.add(id);
                } else {
                    erraram.add(id);
                }
            }
        }

        letrasUsadas.addAll(letrasAAdicionar);

        boolean alguemAcertouPalavraCompleta = false;
        for (Map.Entry<Integer, String> entry : jogadas.entrySet()) {
            String jogada = entry.getValue();
            if (jogada != null && jogada.trim().toUpperCase().equals(palavra)) {
                alguemAcertouPalavraCompleta = true;
                if (!vencedores.contains(entry.getKey())) vencedores.add(entry.getKey());
            }
        }

        if (alguemAcertouPalavraCompleta) {
            finalizado = true;
        } else if (palavraDescoberta()) {
            finalizado = true;
            for (int id : acertaram) {
                if (!vencedores.contains(id)) vencedores.add(id);
            }
        }

        for (int id : erraram) {
            consumirTentativa(id);
        }

        // NOVO: Verificar fim de jogo apenas depois de processar todos os erros da ronda
        verificarFimDeJogo();
    }

    private boolean palavraDescoberta() {
        for (char c : palavra.toCharArray()) {
            if (!letrasUsadas.contains(c)) {
                return false;
            }
        }
        return true;
    }

    public synchronized String getMascara() {
        StringBuilder sb = new StringBuilder();

        for (char c : palavra.toCharArray()) {
            if (letrasUsadas.contains(c)) {
                sb.append(c).append(' ');
            } else {
                sb.append('_').append(' ');
            }
        }

        return sb.toString().trim();
    }

    public synchronized int getTentativasRestantes(int idJogador) {
        return tentativasPorJogador.getOrDefault(idJogador, 0);
    }

    public synchronized String getLetrasUsadasStr() {
        List<Character> lista = new ArrayList<>(letrasUsadas);
        Collections.sort(lista);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lista.size(); i++) {
            sb.append(lista.get(i));
            if (i < lista.size() - 1) {
                sb.append(",");
            }
        }

        return sb.toString();
    }

    public synchronized boolean isFinalizado() {
        return finalizado;
    }

    public synchronized void setFinalizado(boolean finalizado) {
        this.finalizado = finalizado;
    }

    public synchronized void eliminarJogador(int idJogador) {
        if (finalizado) return;
        tentativasPorJogador.put(idJogador, 0);
        // Não chamamos verificarFimDeJogo aqui porque o GestorDeJogo
        // vai decidir se o jogo continua com base nos jogadores ligados.
    }

    public synchronized void consumirTentativa(int idJogador) {
        int t = tentativasPorJogador.getOrDefault(idJogador, 0);
        if (t > 0) {
            tentativasPorJogador.put(idJogador, t - 1);
        }
    }

    /**
     * Verifica se o jogo deve terminar após o processamento de todas as jogadas da ronda.
     */
    public synchronized void verificarFimDeJogo() {
        if (finalizado) return;

        // Se alguém adivinhou a palavra, já está na lista de vencedores
        if (!vencedores.isEmpty()) {
            finalizado = true;
            return;
        }

        // Verificar se TODOS ficaram sem tentativas
        boolean todosMortos = true;
        for (int t : tentativasPorJogador.values()) {
            if (t > 0) {
                todosMortos = false;
                break;
            }
        }

        if (todosMortos) {
            finalizado = true;
            // Neste caso (derrota total), vencedores continua vazio.
        }
    }

    public synchronized List<Integer> getVencedores() {
        return new ArrayList<>(vencedores);
    }

    public String getPalavra() {
        return palavra;
    }
}
