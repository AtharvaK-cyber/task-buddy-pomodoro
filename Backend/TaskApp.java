import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/*
  TaskApp.java
  - single-file Java backend serving frontend and API
  - tasks persist to tasks.json, pomodoro sessions persist to sessions.json
  - edit the FRONTEND_DIR if your frontend location differs
*/

public class TaskApp {

    // -------------------- Models --------------------
    static class Task {
        String id;
        String title;
        String due; // yyyy-MM-dd
        boolean completed;
        String priority; // High/Medium/Low
        long daysLeft;
        String tags;

        Task(String id, String title, String due, String tags) {
            this.id = id;
            this.title = title;
            this.due = due;
            this.tags = tags == null ? "" : tags;
            this.completed = false;
            computeMeta();
        }

        void computeMeta() {
            try {
                LocalDate d = LocalDate.parse(due);
                LocalDate now = LocalDate.now();
                daysLeft = Duration.between(now.atStartOfDay(), d.atStartOfDay()).toDays();
                if (daysLeft <= 3) priority = "High";
                else if (daysLeft <= 7) priority = "Medium";
                else priority = "Low";
            } catch (Exception e) {
                daysLeft = Long.MAX_VALUE;
                priority = "Low";
            }
        }
    }

    static class PomodoroSession {
        String id;
        String taskId;
        String start; // ISO instant
        String end;   // ISO instant
        boolean completed;
        PomodoroSession(String id, String taskId, String start) {
            this.id = id; this.taskId = taskId; this.start = start; this.end = ""; this.completed = false;
        }
    }

    // -------------------- Storage & Paths --------------------
    static List<Task> taskList = Collections.synchronizedList(new ArrayList<>());
    static List<PomodoroSession> sessions = Collections.synchronizedList(new ArrayList<>());

    // Adjust if your folders are in different locations
    static final Path FRONTEND_DIR = Paths.get("../frontend").toAbsolutePath().normalize();
    static final Path DATA_FILE = Paths.get("tasks.json").toAbsolutePath().normalize();
    static final Path SESSIONS_FILE = Paths.get("sessions.json").toAbsolutePath().normalize();
    static final DateTimeFormatter DF = DateTimeFormatter.ISO_LOCAL_DATE;

    // -------------------- Main --------------------
    public static void main(String[] args) throws Exception {
        loadTasksFromDisk();
        loadSessionsFromDisk();

        int port = 8083; // change if needed
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // API
        server.createContext("/addTask", TaskApp::handleAddTask);
        server.createContext("/editTask", TaskApp::handleEditTask);
        server.createContext("/deleteTask", TaskApp::handleDeleteTask);
        server.createContext("/toggleComplete", TaskApp::handleToggleComplete);
        server.createContext("/tasks", TaskApp::handleTasks);
        server.createContext("/exportCSV", TaskApp::handleExportCSV);
        // Pomodoro
        server.createContext("/pomodoro/start", TaskApp::handlePomodoroStart);
        server.createContext("/pomodoro/stop", TaskApp::handlePomodoroStop);
        server.createContext("/pomodoro/stats", TaskApp::handlePomodoroStats);

        // static frontend
        server.createContext("/", TaskApp::handleStatic);

        server.start();
        System.out.println("Server running on http://localhost:" + port + "/");
        System.out.println("Serving frontend from: " + FRONTEND_DIR.toString());
        System.out.println("Data file: " + DATA_FILE.toString());
        System.out.println("Sessions file: " + SESSIONS_FILE.toString());
    }

    // -------------------- API Handlers --------------------
    static void handleAddTask(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(405,-1); ex.close(); return; }
        String body = new BufferedReader(new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)).lines().collect(Collectors.joining());
        Map<String,String> map = parseForm(body);
        String title = urlDecode(map.getOrDefault("title","Untitled"));
        String due = urlDecode(map.getOrDefault("due", LocalDate.now().format(DF)));
        String tags = urlDecode(map.getOrDefault("tags",""));
        String id = UUID.randomUUID().toString();
        Task t = new Task(id, title, due, tags);
        synchronized(taskList){ taskList.add(t); }
        saveTasksToDisk();
        sendPlain(ex,200,"OK");
    }

    static void handleEditTask(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(405,-1); ex.close(); return; }
        String body = new BufferedReader(new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)).lines().collect(Collectors.joining());
        Map<String,String> map = parseForm(body);
        String id = urlDecode(map.getOrDefault("id",""));
        String title = urlDecode(map.getOrDefault("title",""));
        String due = urlDecode(map.getOrDefault("due",""));
        String tags = urlDecode(map.getOrDefault("tags",""));
        synchronized(taskList){
            for(Task t: taskList){
                if(t.id.equals(id)){
                    if(!title.isEmpty()) t.title = title;
                    if(!due.isEmpty()) t.due = due;
                    t.tags = tags;
                    t.computeMeta();
                    break;
                }
            }
        }
        saveTasksToDisk();
        sendPlain(ex,200,"OK");
    }

    static void handleDeleteTask(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(405,-1); ex.close(); return; }
        String body = new BufferedReader(new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)).lines().collect(Collectors.joining());
        Map<String,String> map = parseForm(body);
        String id = urlDecode(map.getOrDefault("id",""));
        synchronized(taskList){ taskList.removeIf(t -> t.id.equals(id)); }
        saveTasksToDisk();
        sendPlain(ex,200,"OK");
    }

    static void handleToggleComplete(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(405,-1); ex.close(); return; }
        String body = new BufferedReader(new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)).lines().collect(Collectors.joining());
        Map<String,String> map = parseForm(body);
        String id = urlDecode(map.getOrDefault("id",""));
        synchronized(taskList){
            for(Task t: taskList) if(t.id.equals(id)){ t.completed = !t.completed; break; }
        }
        saveTasksToDisk();
        sendPlain(ex,200,"OK");
    }

    static void handleTasks(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(405,-1); ex.close(); return; }
        synchronized(taskList){ taskList.forEach(Task::computeMeta); }
        List<Task> sorted;
        synchronized(taskList){
            sorted = taskList.stream()
                .sorted(Comparator.comparing((Task t)->priorityRank(t.priority)).thenComparingLong(t->t.daysLeft))
                .collect(Collectors.toList());
        }
        List<Map<String,Object>> out = new ArrayList<>();
        for(Task t: sorted){
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id", t.id);
            m.put("title", t.title);
            m.put("due", t.due);
            m.put("completed", t.completed);
            m.put("priority", t.priority);
            m.put("daysLeft", t.daysLeft);
            m.put("tags", t.tags);
            out.add(m);
        }
        String json = new GsonLite().toJson(out);
        ex.getResponseHeaders().add("Content-Type","application/json; charset=utf-8");
        sendBytes(ex,200,json.getBytes(StandardCharsets.UTF_8));
    }

    static void handleExportCSV(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(405,-1); ex.close(); return; }
        StringBuilder sb = new StringBuilder();
        sb.append("id,title,due,completed,priority,daysLeft,tags\n");
        synchronized(taskList){
            for(Task t: taskList){
                sb.append(csvEscape(t.id)).append(",").append(csvEscape(t.title)).append(",")
                  .append(csvEscape(t.due)).append(",").append(t.completed).append(",")
                  .append(csvEscape(t.priority)).append(",").append(t.daysLeft).append(",")
                  .append(csvEscape(t.tags)).append("\n");
            }
        }
        byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type","text/csv; charset=utf-8");
        ex.getResponseHeaders().add("Content-Disposition","attachment; filename=\"tasks.csv\"");
        sendBytes(ex,200,data);
    }

    // -------------------- Pomodoro Handlers --------------------
    static void handlePomodoroStart(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(405,-1); ex.close(); return; }
        String body = new BufferedReader(new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)).lines().collect(Collectors.joining());
        Map<String,String> map = parseForm(body);
        String taskId = urlDecode(map.getOrDefault("taskId",""));
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        PomodoroSession s = new PomodoroSession(id, taskId, now);
        synchronized(sessions){ sessions.add(s); }
        saveSessionsToDisk();
        sendPlain(ex,200,id);
    }

    static void handlePomodoroStop(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(405,-1); ex.close(); return; }
        String body = new BufferedReader(new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)).lines().collect(Collectors.joining());
        Map<String,String> map = parseForm(body);
        String sid = urlDecode(map.getOrDefault("sessionId",""));
        synchronized(sessions){
            for(PomodoroSession s: sessions) if(s.id.equals(sid)){ s.end = Instant.now().toString(); s.completed = true; break; }
        }
        saveSessionsToDisk();
        sendPlain(ex,200,"OK");
    }

    static void handlePomodoroStats(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(405,-1); ex.close(); return; }
        Map<String,Object> result = new LinkedHashMap<>();
        synchronized(sessions){
            result.put("totalSessions", sessions.size());
            Map<String,Integer> perTask = new HashMap<>();
            for(PomodoroSession s: sessions) perTask.put(s.taskId, perTask.getOrDefault(s.taskId,0)+1);
            result.put("perTask", perTask);
        }
        String json = new GsonLite().toJson(result);
        ex.getResponseHeaders().add("Content-Type","application/json; charset=utf-8");
        sendBytes(ex,200,json.getBytes(StandardCharsets.UTF_8));
    }

    // -------------------- Static file serving --------------------
    static void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path == null || path.equals("/")) path = "/index.html";
        Path file = FRONTEND_DIR.resolve(path.substring(1)).normalize();
        if (!file.startsWith(FRONTEND_DIR) || !Files.exists(file)) {
            Path index = FRONTEND_DIR.resolve("index.html");
            if (Files.exists(index)) { sendFile(ex,index); return; }
            sendPlain(ex,404,"404 Not Found"); return;
        }
        sendFile(ex,file);
    }

    // -------------------- Helpers --------------------
    static void sendFile(HttpExchange ex, Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().add("Content-Type", guessMime(file.toString()));
        sendBytes(ex,200,bytes);
    }

    static void sendPlain(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type","text/plain; charset=utf-8");
        sendBytes(ex,code,b);
    }

    static void sendBytes(HttpExchange ex, int code, byte[] bytes) throws IOException {
        addCommonCors(ex);
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    static void addCommonCors(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    static Map<String,String> parseForm(String body) {
        Map<String,String> map = new HashMap<>();
        if (body == null || body.isEmpty()) return map;
        for (String p : body.split("&")) {
            if (p.contains("=")) {
                String[] kv = p.split("=",2);
                map.put(kv[0], kv.length>1 ? kv[1] : "");
            }
        }
        return map;
    }

    static String urlDecode(String s) { try { return URLDecoder.decode(s==null?"":s, StandardCharsets.UTF_8.name()); } catch(Exception e){ return s; } }

    static int priorityRank(String p) { if ("High".equals(p)) return 0; if ("Medium".equals(p)) return 1; return 2; }

    static String guessMime(String f) {
        f = f.toLowerCase();
        if (f.endsWith(".html")) return "text/html";
        if (f.endsWith(".css")) return "text/css";
        if (f.endsWith(".js")) return "application/javascript";
        if (f.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    static String csvEscape(String s) {
        if (s == null) return "";
        String out = s.replace("\"","\"\"");
        if (out.contains(",") || out.contains("\"") || out.contains("\n")) out = "\"" + out + "\"";
        return out;
    }

    // -------------------- Persistence --------------------
    static synchronized void saveTasksToDisk() {
        try {
            List<Map<String,Object>> out = new ArrayList<>();
            synchronized(taskList) {
                for(Task t: taskList) {
                    Map<String,Object> m = new LinkedHashMap<>();
                    m.put("id", t.id);
                    m.put("title", t.title);
                    m.put("due", t.due);
                    m.put("completed", t.completed);
                    m.put("tags", t.tags);
                    out.add(m);
                }
            }
            String json = new GsonLite().toJson(out);
            Files.write(DATA_FILE, json.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            System.err.println("Failed to save tasks: " + e.getMessage());
        }
    }

    static synchronized void loadTasksFromDisk() {
        try {
            if (!Files.exists(DATA_FILE)) return;
            String json = new String(Files.readAllBytes(DATA_FILE), StandardCharsets.UTF_8);
            List<Map<String,String>> arr = new GsonLite().fromJsonList(json);
            for (Map<String,String> m : arr) {
                String id = String.valueOf(m.getOrDefault("id", UUID.randomUUID().toString()));
                String title = String.valueOf(m.getOrDefault("title", "Untitled"));
                String due = String.valueOf(m.getOrDefault("due", LocalDate.now().format(DF)));
                boolean completed = Boolean.parseBoolean(String.valueOf(m.getOrDefault("completed","false")));
                String tags = String.valueOf(m.getOrDefault("tags",""));
                Task t = new Task(id, title, due, tags);
                t.completed = completed;
                t.computeMeta();
                taskList.add(t);
            }
        } catch (Exception e) {
            System.err.println("Failed to load tasks: " + e.getMessage());
        }
    }

    static synchronized void saveSessionsToDisk() {
        try {
            List<Map<String,Object>> out = new ArrayList<>();
            synchronized(sessions) {
                for(PomodoroSession s: sessions) {
                    Map<String,Object> m = new LinkedHashMap<>();
                    m.put("id", s.id);
                    m.put("taskId", s.taskId);
                    m.put("start", s.start);
                    m.put("end", s.end);
                    m.put("completed", s.completed);
                    out.add(m);
                }
            }
            String json = new GsonLite().toJson(out);
            Files.write(SESSIONS_FILE, json.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            System.err.println("Failed to save sessions: " + e.getMessage());
        }
    }

    static synchronized void loadSessionsFromDisk() {
        try {
            if (!Files.exists(SESSIONS_FILE)) return;
            String json = new String(Files.readAllBytes(SESSIONS_FILE), StandardCharsets.UTF_8);
            List<Map<String,String>> arr = new GsonLite().fromJsonList(json);
            for (Map<String,String> m : arr) {
                String id = String.valueOf(m.getOrDefault("id", UUID.randomUUID().toString()));
                String taskId = String.valueOf(m.getOrDefault("taskId",""));
                String start = String.valueOf(m.getOrDefault("start",""));
                String end = String.valueOf(m.getOrDefault("end",""));
                boolean completed = Boolean.parseBoolean(String.valueOf(m.getOrDefault("completed","false")));
                PomodoroSession s = new PomodoroSession(id, taskId, start);
                s.end = end; s.completed = completed;
                sessions.add(s);
            }
        } catch (Exception e) {
            System.err.println("Failed to load sessions: " + e.getMessage());
        }
    }

    // -------------------- Tiny JSON helper (GsonLite) --------------------
    static class GsonLite {
        String toJson(Object o) {
            if (o instanceof Map) return mapToJson((Map<?,?>)o);
            if (o instanceof List) {
                StringBuilder sb = new StringBuilder();
                sb.append('[');
                boolean first = true;
                for (Object e : (List<?>) o) {
                    if (!first) sb.append(',');
                    sb.append(toJson(e));
                    first = false;
                }
                sb.append(']');
                return sb.toString();
            }
            if (o instanceof String) return "\"" + escape((String)o) + "\"";
            if (o instanceof Number || o instanceof Boolean) return String.valueOf(o);
            return "\"" + escape(String.valueOf(o)) + "\"";
        }
        String mapToJson(Map<?,?> m) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?,?> e : m.entrySet()) {
                if (!first) sb.append(',');
                sb.append(toJson(String.valueOf(e.getKey())));
                sb.append(':');
                sb.append(toJson(e.getValue()));
                first = false;
            }
            sb.append('}');
            return sb.toString();
        }
        String escape(String s) {
            return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","");
        }

        // Parser: returns List<Map<String,String>>
        List<Map<String,String>> fromJsonList(String s) {
            s = s.trim();
            List<Map<String,String>> out = new ArrayList<>();
            if (!s.startsWith("[")) return out;
            int i = 1;
            while (i < s.length()) {
                int o = s.indexOf('{', i);
                if (o < 0) break;
                int c = findMatchingBrace(s, o);
                if (c < 0) break;
                String obj = s.substring(o+1, c).trim();
                Map<String,String> map = new HashMap<>();
                List<String> parts = splitTopLevel(obj, ',');
                for (String part : parts) {
                    List<String> kv = splitTopLevel(part, ':');
                    if (kv.size() < 2) continue;
                    String key = trimQuotes(kv.get(0));
                    String val = kv.get(1).trim();
                    if (val.startsWith("\"")) val = trimQuotes(val);
                    map.put(key, val);
                }
                out.add(map);
                i = c+1;
            }
            return out;
        }
        int findMatchingBrace(String s, int start) {
            int depth = 0;
            for (int i = start; i < s.length(); ++i) {
                char ch = s.charAt(i);
                if (ch == '{') depth++;
                else if (ch == '}') { depth--; if (depth == 0) return i; }
            }
            return -1;
        }
        List<String> splitTopLevel(String s, char sep) {
            List<String> parts = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean inQuotes = false;
            for (int i = 0; i < s.length(); ++i) {
                char ch = s.charAt(i);
                if (ch == '"' ) inQuotes = !inQuotes;
                if (ch == sep && !inQuotes) { parts.add(cur.toString()); cur.setLength(0); }
                else cur.append(ch);
            }
            if (cur.length() > 0) parts.add(cur.toString());
            return parts;
        }
        String trimQuotes(String s) { s = s.trim(); if (s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length()-1); return s; }
    }
}
