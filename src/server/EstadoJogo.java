package server;

import java.util.*;

public class EstadoJogo {

    private final String palavra;
    private final Set<Character> letrasUsadas;
    private final List<Integer> vencedores;

    private int tentativasRestantes;
    private boolean finalizado;

    public EstadoJogo(String palavra, int maxTentativas) {
        this.palavra = palavra.toUpperCase();
        this.tentativasRestantes = maxTentativas;
        this.letrasUsadas = new HashSet<>();
        this.vencedores = new ArrayList<>();
        this.finalizado = false;
    }

    public synchronized boolean processarChute(String chute, int idJogador) {
        if (finalizado || chute == null || chute.isBlank()) {
            return false;
        }

        chute = chute.trim().toUpperCase();
        boolean correto = false;

        if (chute.length() == 1) {
            char letra = chute.charAt(0);

            if (!Character.isLetter(letra)) {
                return false;
            }

            if (!letrasUsadas.contains(letra)) {
                letrasUsadas.add(letra);

                if (palavra.indexOf(letra) >= 0) {
                    correto = true;

                    if (palavraDescoberta()) {
                        finalizado = true;
                        if (!vencedores.contains(idJogador)) {
                            vencedores.add(idJogador);
                        }
                    }
                } else {
                    tentativasRestantes--;
                }
            }
        } else {
            if (chute.equals(palavra)) {
                correto = true;
                finalizado = true;

                if (!vencedores.contains(idJogador)) {
                    vencedores.add(idJogador);
                }
            } else {
                tentativasRestantes--;
            }
        }

        if (tentativasRestantes <= 0) {
            finalizado = true;
        }

        return correto;
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

    public synchronized int getTentativasRestantes() {
        return tentativasRestantes;
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

    public synchronized List<Integer> getVencedores() {
        return new ArrayList<>(vencedores);
    }

    public String getPalavra() {
        return palavra;
    }
}