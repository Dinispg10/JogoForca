package server;

import java.util.*;

/**
 * Representa o estado interno e a lógica de negócio de uma partida de Jogo da Forca.
 * Esta classe é responsável por validar jogadas, atualizar a máscara da palavra,
 * gerir tentativas individuais e determinar as condições de vitória ou derrota.
 * 
 * <p>Todas as operações que alteram ou lêem o estado são sincronizadas para garantir 
 * thread-safety num ambiente multijogador competitivo.</p>
 */
public class EstadoJogo {

    private final String palavra;
    private final Set<Character> letrasUsadas;
    private final List<Integer> vencedores;

    private final Map<Integer, Integer> tentativasPorJogador;
    private final int maxTentativas;
    
    private boolean finalizado;

    /**
     * Inicializa um novo estado de jogo.
     * @param palavra A palavra secreta da partida.
     * @param maxTentativas O limite de erros permitidos para cada jogador.
     */
    public EstadoJogo(String palavra, int maxTentativas) {
        this.palavra = palavra.toUpperCase();
        this.maxTentativas = maxTentativas;
        this.tentativasPorJogador = new HashMap<>();
        this.letrasUsadas = new HashSet<>();
        this.vencedores = new ArrayList<>();
        this.finalizado = false;
    }

    /**
     * Regista um novo jogador no estado inicial.
     * @param idJogador ID único do jogador.
     */
    public synchronized void adicionarJogador(int idJogador) {
        tentativasPorJogador.put(idJogador, maxTentativas);
    }

    /**
     * Processa o conjunto de jogadas recebidas no final de uma ronda.
     * Aplica as regras de negócio: decremento de tentativas por erro, 
     * descoberta de letras e verificação de adivinhação da palavra completa.
     * 
     * @param jogadas Mapa contendo os IDs dos jogadores e as suas respetivas jogadas (letra ou palavra).
     */
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
                break;
            }
        }

        if (alguemAcertouPalavraCompleta || palavraDescoberta()) {
            finalizado = true;
            for (int id : acertaram) {
                if (!vencedores.contains(id)) vencedores.add(id);
            }
        }

        for (int id : erraram) {
            consumirTentativa(id);
        }

        // Verifica condições de término (morte de todos ou vitória) após processar erros
        verificarFimDeJogo();
    }

    /**
     * Verifica se todas as letras da palavra secreta já foram descobertas.
     */
    private boolean palavraDescoberta() {
        for (char c : palavra.toCharArray()) {
            if (!letrasUsadas.contains(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Retorna a representação visual da palavra (máscara).
     * @return String com letras descobertas e underscores para as ocultas.
     */
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

    /**
     * Obtém as tentativas restantes para um jogador específico.
     */
    public synchronized int getTentativasRestantes(int idJogador) {
        return tentativasPorJogador.getOrDefault(idJogador, 0);
    }

    /**
     * Retorna uma lista formatada das letras já utilizadas.
     */
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

    /**
     * Remove um jogador do jogo (ex: por desconexão), esgotando as suas tentativas.
     */
    public synchronized void eliminarJogador(int idJogador) {
        if (finalizado) return;
        tentativasPorJogador.put(idJogador, 0);
    }

    /**
     * Decrementa uma tentativa do jogador indicado.
     */
    public synchronized void consumirTentativa(int idJogador) {
        int t = tentativasPorJogador.getOrDefault(idJogador, 0);
        if (t > 0) {
            tentativasPorJogador.put(idJogador, t - 1);
        }
    }

    /**
     * Avalia se as condições globais de fim de jogo foram atingidas.
     * O jogo termina se houver vencedores ou se todos os jogadores ficarem sem tentativas.
     */
    public synchronized void verificarFimDeJogo() {
        if (finalizado) return;

        if (!vencedores.isEmpty()) {
            finalizado = true;
            return;
        }

        boolean todosMortos = true;
        for (int t : tentativasPorJogador.values()) {
            if (t > 0) {
                todosMortos = false;
                break;
            }
        }

        if (todosMortos) {
            finalizado = true;
        }
    }

    public synchronized List<Integer> getVencedores() {
        return new ArrayList<>(vencedores);
    }

    public String getPalavra() {
        return palavra;
    }
}
