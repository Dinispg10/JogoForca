package comum;

/**
 * Constantes e métodos utilitários para o protocolo textual do Jogo da Forca.
 *
 * Protocolo Servidor → Cliente:
 *   BEM_VINDO;<id>;<total_jogadores>
 *   INICIO;<mascara>;<tentativas>;<tempo_ronda_ms>
 *   RODADA;<numero>;<mascara>;<tentativas>;<letras_usadas>
 *   ESTADO;<mascara>;<tentativas>;<letras_usadas>
 *   FIM_VITORIA;<ids_vencedores>;<palavra>;<motivo>
 *   FIM_PERDA;<palavra>;<motivo>
 *   CHEIO
 *   LOBBY_TIMEOUT
 *
 * Protocolo Cliente → Servidor:
 *   jogada;<texto>
 */
public class ProtocolMessages {

    public static final String BEM_VINDO = "BEM_VINDO";
    public static final String INICIO = "INICIO";
    public static final String RODADA = "RODADA";
    public static final String ESTADO = "ESTADO";
    public static final String FIM_VITORIA = "FIM_VITORIA";
    public static final String FIM_PERDA = "FIM_PERDA";
    public static final String CHEIO = "CHEIO";
    public static final String LOBBY_TIMEOUT = "LOBBY_TIMEOUT";
    public static final String jogada = "jogada";

    public static String bemVindo(int idJogador, int totalJogadores) {
        return BEM_VINDO + ";" + idJogador + ";" + totalJogadores;
    }

    public static String inicio(String mascara, int tentativas, long tempoRondaMs) {
        return INICIO + ";" + mascara + ";" + tentativas + ";" + tempoRondaMs;
    }

    public static String rodada(int numero, String mascara, int tentativas, String letrasUsadas) {
        return RODADA + ";" + numero + ";" + mascara + ";" + tentativas + ";" +
                ((letrasUsadas == null || letrasUsadas.isBlank()) ? "-" : letrasUsadas);
    }

    public static String estado(String mascara, int tentativas, String letrasUsadas) {
        return ESTADO + ";" + mascara + ";" + tentativas + ";" +
                ((letrasUsadas == null || letrasUsadas.isBlank()) ? "-" : letrasUsadas);
    }

    public static String fimVitoria(String idsVencedores, String palavra) {
        return FIM_VITORIA + ";" + idsVencedores + ";" + palavra;
    }

    public static String fimPerda(String palavra) {
        return FIM_PERDA + ";" + palavra;
    }

    public static String lobbyTimeout() {
        return LOBBY_TIMEOUT;
    }

    public static String jogada(String texto) {
        return jogada + ";" + texto.toUpperCase();
    }

    public static String parseChute(String linha) {
        if (linha == null || linha.isBlank()) {
            return null;
        }

        String[] partes = linha.split(";", 2);

        if (partes.length == 2 && jogada.equals(partes[0])) {
            return partes[1].trim().toUpperCase();
        }

        return null;
    }
}
