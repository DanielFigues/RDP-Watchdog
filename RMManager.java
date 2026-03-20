import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class RMManager {

    // Configuracoes do programa
    private static final String PROCESS_NAME = "RM.exe";
    private static final long MAX_IDLE_MS = 15 * 60 * 1000L; // 15 minutos
    private static final long INTERVAL_MS = 10_000L; // 10 segundos
    private static final long GRACE_MS = 10 * 60 * 1000L; // 10 minutos de tolerancia
    private static final String LOG_FILE = "rm-manager.log";
    private static final String[] EXCLUDED_USERS = { "rafael.souza" };

    // Controle de sessoes conhecidas
    private static String knownSessions[] = new String[100];
    private static int totalKnown = 0;

    // Controle de inatividade
    private static String trackedSid[] = new String[100];
    private static long lastQuser[] = new long[100];
    private static long lastReset[] = new long[100];
    private static int totalTracked = 0;

    // Cache do quser, atualizado uma vez por ciclo
    private static String quserLines[] = new String[100];
    private static int totalQuserLines = 0;

    private static final long START_TIME = System.currentTimeMillis();
    private static PrintWriter logWriter;

    static {
        try {
            logWriter = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(LOG_FILE, true), "UTF-8"), true);
        } catch (Exception e) {
            System.err.println("Nao foi possivel abrir o arquivo de log: " + e.getMessage());
        }
    }

    // Verifica se uma sessao ja e conhecida
    private static boolean isSessionKnown(String sid) {
        for (int i = 0; i < totalKnown; i++) {
            if (knownSessions[i].equals(sid))
                return true;
        }
        return false;
    }

    // Adiciona uma sessao a lista de conhecidas
    private static void addKnownSession(String sid) {
        if (totalKnown < knownSessions.length) {
            knownSessions[totalKnown] = sid;
            totalKnown++;
        }
    }

    // Remove uma sessao da lista de conhecidas
    private static void removeKnownSession(String sid) {
        for (int i = 0; i < totalKnown; i++) {
            if (knownSessions[i].equals(sid)) {
                for (int j = i; j < totalKnown - 1; j++) {
                    knownSessions[j] = knownSessions[j + 1];
                }
                totalKnown--;
                return;
            }
        }
    }

    // Remove uma sessao do controle de inatividade
    private static void removeTrackedSession(String sid) {
        for (int i = 0; i < totalTracked; i++) {
            if (trackedSid[i].equals(sid)) {
                for (int j = i; j < totalTracked - 1; j++) {
                    trackedSid[j] = trackedSid[j + 1];
                    lastQuser[j] = lastQuser[j + 1];
                    lastReset[j] = lastReset[j + 1];
                }
                totalTracked--;
                return;
            }
        }
    }

    // Remove sessoes conhecidas que nao tem mais RM aberto
    private static void cleanKnownSessions(String active[], int totalActive) {
        for (int i = totalKnown - 1; i >= 0; i--) {
            boolean found = false;
            for (int j = 0; j < totalActive; j++) {
                if (knownSessions[i].equals(active[j])) {
                    found = true;
                    break;
                }
            }
            if (!found)
                removeKnownSession(knownSessions[i]);
        }
    }

    // Remove sessoes do controle de inatividade que nao tem mais RM aberto
    private static void cleanTrackedSessions(String active[], int totalActive) {
        for (int i = totalTracked - 1; i >= 0; i--) {
            boolean found = false;
            for (int j = 0; j < totalActive; j++) {
                if (trackedSid[i].equals(active[j])) {
                    found = true;
                    break;
                }
            }
            if (!found)
                removeTrackedSession(trackedSid[i]);
        }
    }

    // Faz o parse de uma linha CSV respeitando campos entre aspas
    private static String[] parseCSV(String line) {
        String cols[] = new String[20];
        int total = 0;
        String aux = "";
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                if (total < cols.length) {
                    cols[total] = aux;
                    total++;
                }
                aux = "";
            } else {
                aux += c;
            }
        }
        if (total < cols.length) {
            cols[total] = aux;
            total++;
        }
        String result[] = new String[total];
        for (int i = 0; i < total; i++) {
            result[i] = cols[i];
        }
        return result;
    }

    // Atualiza o cache do quser
    private static void refreshQuser() throws Exception {
        Process p = new ProcessBuilder("quser")
                .redirectErrorStream(true)
                .start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        totalQuserLines = 0;
        String line;
        while ((line = r.readLine()) != null) {
            if (totalQuserLines < quserLines.length) {
                quserLines[totalQuserLines] = line;
                totalQuserLines++;
            }
        }
        r.close();
        p.destroy();
    }

    // Retorna a linha do cache do quser que contem a sessao, ou null se nao encontrar
    private static String getQuserLine(String sessionId) {
        for (int i = 0; i < totalQuserLines; i++) {
            if (quserLines[i].contains(sessionId))
                return quserLines[i];
        }
        return null;
    }

    // Retorna as sessoes que tem RM.exe aberto
    private static String[] getSessionsWithRM() throws Exception {
        String sessions[] = new String[100];
        int total = 0;
        Process p = new ProcessBuilder("tasklist", "/FI", "IMAGENAME eq " + PROCESS_NAME, "/FO", "CSV")
                .redirectErrorStream(true)
                .start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line;
        while ((line = r.readLine()) != null) {
            if (line.toLowerCase().contains(PROCESS_NAME.toLowerCase())) {
                String cols[] = parseCSV(line);
                if (cols.length >= 4) {
                    String sid = cols[3].trim();
                    if (sid.matches("\\d+") && total < sessions.length) {
                        sessions[total] = sid;
                        total++;
                    }
                }
            }
        }
        r.close();
        p.destroy();
        String result[] = new String[total];
        for (int i = 0; i < total; i++)
            result[i] = sessions[i];
        return result;
    }

    // Retorna o nome do usuario de uma sessao usando o cache do quser
    private static String getSessionName(String sessionId) {
        String line = getQuserLine(sessionId);
        if (line == null)
            return "sessao " + sessionId;
        String parts[] = line.trim().replaceAll("\\s+", " ").split(" ");
        return parts.length > 1 ? parts[1] : "sessao " + sessionId;
    }

    // Le o valor de idle(inatividade) do quser em minutos para uma sessao usando o cache
    private static long readQuserIdleMinutes(String sessionId) {
        String line = getQuserLine(sessionId);
        if (line == null)
            return -1L;
        String parts[] = line.trim().replaceAll("\\s+", " ").split(" ");
        if (parts.length <= 4)
            return -1L;
        String idle = parts[4];
        try {
            if (idle.equals("."))
                return 0L;
            if (idle.matches("\\d+"))
                return Long.parseLong(idle);
            if (idle.matches("\\d+:\\d+")) {
                String t[] = idle.split(":");
                return Long.parseLong(t[0]) * 60 + Long.parseLong(t[1]);
            }
            if (idle.matches("\\d+\\+\\d+:\\d+")) {
                String t[] = idle.split("[+:]");
                return Long.parseLong(t[0]) * 1440 + Long.parseLong(t[1]) * 60 + Long.parseLong(t[2]);
            }
        } catch (NumberFormatException e) {
            log("Formato de idle inesperado para sessao " + sessionId + ": " + idle);
        }
        return -1L;
    }

    // Compara o valor atual do quser com o anterior
    // Se for zero ou diminuiu, houve movimento então reseta o timer
    private static long getSessionIdleMs(String sessionId) {
        long currentQuser = readQuserIdleMinutes(sessionId);

        // Nao conseguiu ler o quser, assume ativo para nao fechar por engano
        if (currentQuser == -1L)
            return 0L;

        int index = -1;
        for (int i = 0; i < totalTracked; i++) {
            if (trackedSid[i].equals(sessionId)) {
                index = i;
                break;
            }
        }

        // Primeira vez que a sessao e vista inicializa com valor atual
        if (index == -1) {
            if (totalTracked < trackedSid.length) {
                trackedSid[totalTracked] = sessionId;
                lastQuser[totalTracked] = currentQuser;
                lastReset[totalTracked] = System.currentTimeMillis();
                totalTracked++;
            }
            return 0L;
        }

        // Se a inatividade e zero ou diminuiu, houve movimento
        if (currentQuser == 0 || currentQuser < lastQuser[index]) {
            lastReset[index] = System.currentTimeMillis();
        }

        lastQuser[index] = currentQuser;

        // Usuario ativo, retorna 0
        if (currentQuser == 0)
            return 0L;

        return System.currentTimeMillis() - lastReset[index];
    }

    // Tenta fechar o RM.exe de uma sessao, retorna true se conseguiu
    private static boolean closeRM(String sid, boolean forced) throws Exception {
        Process p;
        if (forced) {
            p = new ProcessBuilder("taskkill", "/F", "/FI", "SESSION eq " + sid, "/IM", PROCESS_NAME, "/T")
                    .redirectErrorStream(true)
                    .start();
        } else {
            p = new ProcessBuilder("taskkill", "/FI", "SESSION eq " + sid, "/IM", PROCESS_NAME, "/T")
                    .redirectErrorStream(true)
                    .start();
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        while (r.readLine() != null) {
        }
        r.close();
        Thread.sleep(forced ? 2000 : 4000);
        String updated[] = getSessionsWithRM();
        for (int i = 0; i < updated.length; i++) {
            if (updated[i].equals(sid))
                return false;
        }
        return true;
    }

    private static void log(String msg) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String line = "[" + ts + "] " + msg;
        System.out.println(line);
        if (logWriter != null)
            logWriter.println(line);
    }

    public static void main(String args[]) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("RM Manager encerrado pelo sistema");
            if (logWriter != null)
                logWriter.close();
        }));

        log("=== RM Manager iniciado ===");
        log("Monitorando todas as sessoes | Fecha RM apos " + (MAX_IDLE_MS / 60000) + " min de inatividade");

        while (true) {
            try {
                boolean inGrace = (System.currentTimeMillis() - START_TIME) < GRACE_MS;

                // Atualiza o cache do quser uma vez por ciclo
                refreshQuser();

                String sessions[] = getSessionsWithRM();

                cleanKnownSessions(sessions, sessions.length);
                cleanTrackedSessions(sessions, sessions.length);

                for (int i = 0; i < sessions.length; i++) {
                    String sid = sessions[i];
                    String name = getSessionName(sid);

                    // Pula usuarios excluidos
                    boolean isExcluded = false;
                    for (int j = 0; j < EXCLUDED_USERS.length; j++) {
                        if (name.toLowerCase().contains(EXCLUDED_USERS[j].toLowerCase())) {
                            isExcluded = true;
                            break;
                        }
                    }
                    if (isExcluded)
                        continue;
                    // Loga quando RM e encontrado pela primeira vez na sessao
                    if (!isSessionKnown(sid)) {
                        log("RM.exe encontrado aberto na sessao " + sid + " (" + name + ")");
                        addKnownSession(sid);
                    }

                    long idleMs = getSessionIdleMs(sid);
                    long idleMin = idleMs / 60000;

                    if (idleMin >= 10) {
                        log("Sessao " + sid + " (" + name + ") inativa ha " + idleMin + " minutos");
                    }

                    if (idleMs >= MAX_IDLE_MS && !inGrace) {
                        log("Sessao " + sid + " (" + name
                                + ") inativa ha 15+ minutos -> Tentativa de fechamento gentil");
                        boolean closed = closeRM(sid, false);

                        if (!closed) {
                            log("Sessao " + sid + " (" + name + ") inativa ha 15+ minutos -> Forcando fechamento");
                            closed = closeRM(sid, true);
                        }

                        if (closed) {
                            log("RM na sessao " + sid + " (" + name + ") fechado com sucesso");
                            removeKnownSession(sid);
                            removeTrackedSession(sid);
                        } else {
                            log("RM na sessao " + sid + " (" + name + ") nao foi possivel fechar");
                        }
                    }
                }

                Thread.sleep(INTERVAL_MS);

            } catch (Exception e) {
                log("Erro no loop principal: " + e.getMessage());
            }
        }
    }
}