package comum;

/**
 * Constantes e métodos utilitários para o protocolo textual do Jogo da Forca.
 * Define a estrutura das mensagens trocadas entre o Servidor e o Cliente para
 * garantir a interoperabilidade e sincronização dos estados de jogo.
 *
 * <p>Estrutura Geral: COMANDO;parametro1;parametro2;...</p>
 *
 * <p>Protocolo Servidor → Cliente:
 * <ul>
 *   <li>BEM_VINDO;id;total_jogadores</li>
 *   <li>INICIO;mascara;tentativas;tempo_ronda_ms</li>
 *   <li>RODADA;numero;mascara;tentativas;letras_usadas</li>
 *   <li>ESTADO;mascara;tentativas;letras_usadas</li>
 *   <li>FIM_VITORIA;ids_vencedores;palavra</li>
 *   <li>FIM_PERDA;palavra</li>
 *   <li>CHEIO</li>
 *   <li>LOBBY_TIMEOUT</li>
 * </ul>
 * </p>
 *
 * <p>Protocolo Cliente → Servidor:
 * <ul>
 *   <li>jogada;texto</li>
 * </ul>
 * </p>
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

    /**
     * Gera a mensagem de boas-vindas enviada ao cliente após a conexão.
     * @param idJogador Identificador único atribuído ao jogador.
     * @param totalJogadores Número total de jogadores na sessão.
     * @return String formatada segundo o protocolo.
     */
    public static String bemVindo(int idJogador, int totalJogadores) {
        return BEM_VINDO + ";" + idJogador + ";" + totalJogadores;
    }

    /**
     * Gera a mensagem de configuração inicial da partida.
     * @param mascara Representação oculta da palavra (ex: _ _ _).
     * @param tentativas Número máximo de erros permitidos para o jogador.
     * @param tempoRondaMs Tempo disponível por ronda em milissegundos.
     * @return String formatada segundo o protocolo.
     */
    public static String inicio(String mascara, int tentativas, long tempoRondaMs) {
        return INICIO + ";" + mascara + ";" + tentativas + ";" + tempoRondaMs;
    }

    /**
     * Gera a mensagem que sinaliza o início de uma nova ronda.
     * @param numero Número sequencial da ronda.
     * @param mascara Máscara atual da palavra.
     * @param tentativas Tentativas restantes do jogador destinatário.
     * @param letrasUsadas Lista de letras já utilizadas por todos os jogadores.
     * @return String formatada segundo o protocolo.
     */
    public static String rodada(int numero, String mascara, int tentativas, String letrasUsadas) {
        return RODADA + ";" + numero + ";" + mascara + ";" + tentativas + ";" +
                ((letrasUsadas == null || letrasUsadas.isBlank()) ? "-" : letrasUsadas);
    }

    /**
     * Gera a mensagem de atualização de estado enviada após o processamento das jogadas.
     * @param mascara Máscara atualizada.
     * @param tentativas Tentativas restantes.
     * @param letrasUsadas Letras globais utilizadas.
     * @return String formatada segundo o protocolo.
     */
    public static String estado(String mascara, int tentativas, String letrasUsadas) {
        return ESTADO + ";" + mascara + ";" + tentativas + ";" +
                ((letrasUsadas == null || letrasUsadas.isBlank()) ? "-" : letrasUsadas);
    }

    /**
     * Gera a mensagem de término de jogo com vitória.
     * @param idsVencedores Lista de IDs dos jogadores vencedores.
     * @param palavra A palavra completa que foi adivinhada.
     * @return String formatada segundo o protocolo.
     */
    public static String fimVitoria(String idsVencedores, String palavra) {
        return FIM_VITORIA + ";" + idsVencedores + ";" + palavra;
    }

    /**
     * Gera a mensagem de término de jogo com derrota.
     * @param palavra A palavra que não foi descoberta.
     * @return String formatada segundo o protocolo.
     */
    public static String fimPerda(String palavra) {
        return FIM_PERDA + ";" + palavra;
    }

    /**
     * Gera a mensagem enviada quando o lobby expira por falta de jogadores.
     * @return String "LOBBY_TIMEOUT".
     */
    public static String lobbyTimeout() {
        return LOBBY_TIMEOUT;
    }

    /**
     * Gera a mensagem de jogada para envio ao servidor.
     * @param texto A letra ou palavra submetida pelo jogador.
     * @return String formatada "jogada;TEXTO".
     */
    public static String jogada(String texto) {
        return jogada + ";" + texto.toUpperCase();
    }

    /**
     * Processa uma linha recebida do cliente para extrair o conteúdo da jogada.
     * @param linha Mensagem bruta do socket.
     * @return O conteúdo da jogada em maiúsculas ou null se for inválida.
     */
    public static String parseJogada(String linha) {
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
