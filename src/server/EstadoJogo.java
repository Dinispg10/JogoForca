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

    public synchronized boolean processarJogada(String jogada, int idJogador) {
        if (finalizado || jogada == null || jogada.isBlank()) {
            return false;
        }

        jogada = jogada.trim().toUpperCase();
        boolean correto = false;

        if (jogada.length() == 1) {
            char letra = jogada.charAt(0);

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
                }
            }
        } else {
            if (jogada.equals(palavra)) {
                correto = true;
                finalizado = true;

                if (!vencedores.contains(idJogador)) {
                    vencedores.add(idJogador);
                }
            }
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

    public synchronized void consumirTentativa(int idJogador) {
        if (finalizado) return;
        
        int t = tentativasPorJogador.getOrDefault(idJogador, 0);
        if (t > 0) {
            tentativasPorJogador.put(idJogador, t - 1);
            
            if (t - 1 <= 0) {
                finalizado = true;
                
                // Conforme pedido: se alguém chega a 0, o jogo acaba e ganham os outros!
                for (Map.Entry<Integer, Integer> entry : tentativasPorJogador.entrySet()) {
                    if (entry.getValue() > 0 && !vencedores.contains(entry.getKey())) {
                        vencedores.add(entry.getKey());
                    }
                }
            }
        }
    }

    public synchronized List<Integer> getVencedores() {
        return new ArrayList<>(vencedores);
    }

    public String getPalavra() {
        return palavra;
    }
}
