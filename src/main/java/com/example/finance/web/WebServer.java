package com.example.finance.web;

import com.example.finance.analysis.FinancialDataFrame;
import com.example.finance.ask.AskService;
import com.example.finance.io.FinancialCsvReader;
import com.example.finance.model.Transaction;
import com.example.finance.util.Json;
import com.example.finance.util.LruCache;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The web dashboard, on the JDK's built-in HTTP server (no framework, ~70 MB of memory).
 *
 * <pre>
 * GET  /                    dashboard
 * GET  /health              liveness
 * GET  /api/summary?d=ID    dashboard data for the sample or an uploaded dataset
 * POST /api/ask             {"question": "...", "dataset": "ID"}
 * POST /api/datasets        CSV body; returns {"id": ...}. Kept in memory (LRU, 20 datasets).
 * GET  /api/eval            latest planner evaluation
 * </pre>
 */
public final class WebServer {
    private static final int MAX_UPLOAD_BYTES = 1_000_000;
    private static final int MAX_QUESTION_CHARS = 300;
    private static final Pattern DATASET_ID = Pattern.compile("^[a-f0-9]{12}$");
    private static final Map<String, String> TYPES = Map.of("html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8", "svg", "image/svg+xml");

    private final FinancialDataFrame sample;
    private final AskService ask;
    private final String currencySymbol;
    private final DashboardService dashboard = new DashboardService();
    private final LruCache<String, List<Transaction>> uploads = new LruCache<>(20);
    private final Map<String, Deque<Long>> askHits = new ConcurrentHashMap<>();

    public WebServer(FinancialDataFrame sample, AskService ask, String currencySymbol) {
        this.sample = sample;
        this.ask = ask;
        this.currencySymbol = currencySymbol;
    }

    public HttpServer start(int port) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", this::handle);
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
            System.out.printf("Dashboard on http://localhost:%d (questions answered by %s)%n", port, ask.llmEnabled() ? "the LLM planner" : "keyword rules");
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("Could not start the server on port " + port, ex);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            ex.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
            if (method.equals("GET") && path.equals("/health")) {
                json(ex, 200, Map.of("status", "ok"));
            } else if (method.equals("GET") && path.equals("/api/summary")) {
                List<Transaction> data = dataset(query(ex, "d"));
                if (data == null) {
                    json(ex, 404, Map.of("detail", "That uploaded dataset has expired. Upload it again."));
                    return;
                }
                Map<String, Object> body = new LinkedHashMap<>(dashboard.summary(data));
                body.put("currency", currencySymbol);
                body.put("llm", ask.llmEnabled());
                json(ex, 200, body);
            } else if (method.equals("POST") && path.equals("/api/ask")) {
                handleAsk(ex);
            } else if (method.equals("POST") && path.equals("/api/datasets")) {
                handleUpload(ex);
            } else if (method.equals("GET") && path.equals("/api/eval")) {
                Path results = Path.of("eval", "results", "latest.json");
                if (Files.exists(results)) {
                    send(ex, 200, "application/json", Files.readString(results));
                } else {
                    json(ex, 200, Map.of("available", false));
                }
            } else if (method.equals("GET")) {
                staticFile(ex, path.equals("/") ? "index.html" : path.substring(1));
            } else {
                json(ex, 405, Map.of("detail", "Method not allowed"));
            }
        } catch (RuntimeException e) {
            json(ex, 500, Map.of("detail", "Something went wrong."));
            System.err.println("Request failed: " + e);
        }
    }

    private void handleAsk(HttpExchange ex) throws IOException {
        if (!allowed(clientIp(ex))) {
            json(ex, 429, Map.of("detail", "Too many questions. Try again in a few minutes."));
            return;
        }
        Map<String, Object> body;
        try {
            body = Json.parseObject(readBody(ex, 10_000));
        } catch (IllegalArgumentException e) {
            json(ex, 400, Map.of("detail", "Send JSON like {\"question\": \"...\"}."));
            return;
        }
        String question = body.get("question") == null ? "" : String.valueOf(body.get("question")).trim();
        if (question.length() < 3 || question.length() > MAX_QUESTION_CHARS) {
            json(ex, 422, Map.of("detail", "Questions need between 3 and " + MAX_QUESTION_CHARS + " characters."));
            return;
        }
        List<Transaction> data = dataset(body.get("dataset") == null ? null : String.valueOf(body.get("dataset")));
        if (data == null) {
            json(ex, 404, Map.of("detail", "That uploaded dataset has expired. Upload it again."));
            return;
        }
        json(ex, 200, ask.ask(question, data));
    }

    private void handleUpload(HttpExchange ex) throws IOException {
        String csv;
        try {
            csv = readBody(ex, MAX_UPLOAD_BYTES);
        } catch (IllegalArgumentException e) {
            json(ex, 413, Map.of("detail", "Files must be under 1 MB."));
            return;
        }
        try {
            FinancialDataFrame frame = FinancialCsvReader.parse(new StringReader(csv));
            String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            uploads.put(id, frame.transactions());
            json(ex, 200, Map.of("id", id, "transactions", frame.transactions().size()));
        } catch (IllegalArgumentException | IOException e) {
            json(ex, 400, Map.of("detail", e.getMessage() == null ? "Couldn't read that CSV." : e.getMessage()));
        }
    }

    private List<Transaction> dataset(String id) {
        if (id == null || id.isBlank() || id.equals("sample")) {
            return sample.transactions();
        }
        return DATASET_ID.matcher(id).matches() ? uploads.get(id).orElse(null) : null;
    }

    /** 30 questions per 10 minutes per client: enough to explore, not enough to drain the free LLM quota. */
    private boolean allowed(String ip) {
        long now = System.currentTimeMillis();
        Deque<Long> hits = askHits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && now - hits.peekFirst() > 600_000) {
                hits.pollFirst();
            }
            if (hits.size() >= 30) {
                return false;
            }
            hits.addLast(now);
            return true;
        }
    }

    private static String clientIp(HttpExchange ex) {
        String forwarded = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        return forwarded != null ? forwarded.split(",")[0].trim() : ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private static String query(HttpExchange ex, String name) {
        String raw = ex.getRequestURI().getQuery();
        if (raw == null) {
            return null;
        }
        for (String part : raw.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv[0].equals(name)) {
                return kv.length > 1 ? kv[1] : "";
            }
        }
        return null;
    }

    private static String readBody(HttpExchange ex, int limit) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new IllegalArgumentException("Body too large");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private void staticFile(HttpExchange ex, String name) throws IOException {
        if (!name.matches("[a-z0-9_-]+\\.(html|js|css|svg)")) {
            json(ex, 404, Map.of("detail", "Not found"));
            return;
        }
        try (InputStream in = WebServer.class.getResourceAsStream("/web/" + name)) {
            if (in == null) {
                json(ex, 404, Map.of("detail", "Not found"));
                return;
            }
            String ext = name.substring(name.lastIndexOf('.') + 1);
            send(ex, 200, TYPES.get(ext), new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static void json(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json", Json.write(body));
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
