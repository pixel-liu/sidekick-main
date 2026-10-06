package com.sidekick.runtime.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sidekick.runtime.task.TaskRunner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RuntimeApiServer implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RuntimeThreadStore store;
    private final TaskRunner runner;
    private final String apiKey;
    private final HttpServer server;
    private final ExecutorService requestExecutor;
    private final ExecutorService turnExecutor;

    public RuntimeApiServer(RuntimeThreadStore store, TaskRunner runner, int port, String apiKey) throws IOException {
        this(store, runner, port, apiKey, 4, 64);
    }

    RuntimeApiServer(RuntimeThreadStore store, TaskRunner runner, int port, String apiKey,
                     int turnThreads, int turnQueueCapacity) throws IOException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Runtime API 需要配置 Sidekick_RUNTIME_API_KEY 或 -DSidekick.runtime.api.key");
        }
        this.store = store;
        this.runner = runner;
        this.apiKey = apiKey;
        this.requestExecutor = boundedExecutor("Sidekick-runtime-http", 8, 128);
        this.turnExecutor = boundedExecutor("Sidekick-runtime-turn",
                Math.max(1, turnThreads), Math.max(1, turnQueueCapacity));
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext("/v1/threads", this::handleThreads);
        this.server.setExecutor(requestExecutor);
    }

    public static String configuredApiKey() {
        String configured = System.getProperty("Sidekick.runtime.api.key");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("Sidekick_RUNTIME_API_KEY");
        }
        return configured;
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handleThreads(HttpExchange exchange) throws IOException {
        try {
            if (!authorized(exchange)) {
                writeJson(exchange, 401, json("error", "unauthorized"));
                return;
            }
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(method) && "/v1/threads".equals(path)) {
                String id = store.createThread();
                writeJson(exchange, 200, json("id", id, "object", "thread"));
                return;
            }
            if ("POST".equals(method) && path.matches("/v1/threads/[^/]+/turns")) {
                handleTurn(exchange, threadId(path));
                return;
            }
            if ("GET".equals(method) && path.matches("/v1/threads/[^/]+/events")) {
                handleEvents(exchange, threadId(path));
                return;
            }
            writeJson(exchange, 404, json("error", "not_found"));
        } catch (Exception e) {
            writeJson(exchange, 500, json("error", messageOf(e)));
        }
    }

    private void handleTurn(HttpExchange exchange, String threadId) throws IOException {
        if (!store.exists(threadId)) {
            writeJson(exchange, 404, json("error", "thread_not_found"));
            return;
        }
        JsonNode body;
        try {
            body = MAPPER.readTree(exchange.getRequestBody());
        } catch (IOException e) {
            writeJson(exchange, 400, json("error", "invalid_json"));
            return;
        }
        if (body == null || !body.isObject()) {
            writeJson(exchange, 400, json("error", "invalid_json"));
            return;
        }
        String input = body.path("input").asText("");
        if (input.isBlank()) {
            writeJson(exchange, 400, json("error", "input_required"));
            return;
        }
        String turnId = "turn_" + Long.toHexString(System.nanoTime());
        store.appendEvent(threadId, "turn.started",
                json("turn_id", turnId, "input", input));
        try {
            turnExecutor.submit(() -> runTurn(threadId, turnId, input));
        } catch (RejectedExecutionException e) {
            store.appendEvent(threadId, "turn.failed",
                    json("turn_id", turnId, "error", "runtime_busy"));
            writeJson(exchange, 429, json("error", "runtime_busy"));
            return;
        }
        writeJson(exchange, 202, json("id", turnId, "object", "turn", "status", "running"));
    }

    private void runTurn(String threadId, String turnId, String input) {
        try {
            String result = runner.run(input);
            store.appendEvent(threadId, "message.delta",
                    json("turn_id", turnId, "content", result));
            store.appendEvent(threadId, "turn.completed",
                    json("turn_id", turnId, "status", "completed"));
        } catch (Exception e) {
            store.appendEvent(threadId, "turn.failed",
                    json("turn_id", turnId, "error", messageOf(e)));
        }
    }

    private void handleEvents(HttpExchange exchange, String threadId) throws IOException {
        if (!store.exists(threadId)) {
            writeJson(exchange, 404, json("error", "thread_not_found"));
            return;
        }
        long after = parseAfter(exchange.getRequestURI().getQuery());
        List<RuntimeEvent> events = store.events(threadId, after);
        byte[] body = formatSse(events).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        String direct = exchange.getRequestHeaders().getFirst("X-Sidekick-API-Key");
        return ("Bearer " + apiKey).equals(auth) || apiKey.equals(direct);
    }

    private static String threadId(String path) {
        String[] parts = path.split("/");
        return parts.length >= 4 ? parts[3] : "";
    }

    private static long parseAfter(String query) {
        if (query == null || query.isBlank()) {
            return 0;
        }
        for (String part : query.split("&")) {
            if (part.startsWith("after=")) {
                try {
                    return Long.parseLong(part.substring("after=".length()));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static String formatSse(List<RuntimeEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (RuntimeEvent event : events) {
            sb.append("id: ").append(event.id()).append('\n');
            sb.append("event: ").append(event.type()).append('\n');
            sb.append("data: ").append(event.data()).append("\n\n");
        }
        return sb.toString();
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String json(String... fields) {
        if (fields.length % 2 != 0) {
            throw new IllegalArgumentException("JSON 字段必须成对提供");
        }
        ObjectNode node = MAPPER.createObjectNode();
        for (int i = 0; i < fields.length; i += 2) {
            node.put(fields[i], fields[i + 1] == null ? "" : fields[i + 1]);
        }
        return node.toString();
    }

    private static String messageOf(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static ExecutorService boundedExecutor(String name, int threads, int queueCapacity) {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, name + "-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Override
    public void close() {
        server.stop(0);
        requestExecutor.shutdownNow();
        turnExecutor.shutdownNow();
    }
}
