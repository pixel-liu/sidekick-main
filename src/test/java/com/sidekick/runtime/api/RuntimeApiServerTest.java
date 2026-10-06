package com.sidekick.runtime.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeApiServerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void exposesThreadTurnAndSseEvents(@TempDir Path tempDir) throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
             RuntimeApiServer server = new RuntimeApiServer(store, prompt -> "reply:" + prompt, 0, "secret")) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();

            HttpResponse<String> created = client.send(request(base + "/v1/threads", "POST", "")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, created.statusCode());
            String threadId = extract(created.body(), "thread_");

            HttpResponse<String> turn = client.send(request(base + "/v1/threads/" + threadId + "/turns", "POST",
                            "{\"input\":\"hello\"}").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, turn.statusCode());

            String events = waitForEvents(client, base, threadId);
            assertTrue(events.contains("event: turn.started"));
            assertTrue(events.contains("event: message.delta"));
            assertTrue(events.contains("reply:hello"));
            assertTrue(events.contains("event: turn.completed"));
        }
    }

    @Test
    void rejectsMissingApiKey(@TempDir Path tempDir) throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
             RuntimeApiServer server = new RuntimeApiServer(store, prompt -> "x", 0, "secret")) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/threads"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(3))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(401, response.statusCode());
        }
    }

    @Test
    void serializesControlCharactersAsValidJson(@TempDir Path tempDir) throws Exception {
        String input = "第一行\t第二行\u0001";
        String reply = "回复\t内容\u0002";
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
             RuntimeApiServer server = new RuntimeApiServer(store, prompt -> reply, 0, "secret")) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            String threadId = createThread(client, base);
            String requestBody = MAPPER.createObjectNode().put("input", input).toString();

            HttpResponse<String> turn = client.send(request(base + "/v1/threads/" + threadId + "/turns",
                            "POST", requestBody).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(202, turn.statusCode());

            String events = waitForEvents(client, base, threadId);
            boolean sawInput = false;
            boolean sawReply = false;
            for (String line : events.lines().toList()) {
                if (!line.startsWith("data: ")) {
                    continue;
                }
                JsonNode data = MAPPER.readTree(line.substring("data: ".length()));
                sawInput |= input.equals(data.path("input").asText());
                sawReply |= reply.equals(data.path("content").asText());
            }
            assertTrue(sawInput);
            assertTrue(sawReply);
        }
    }

    @Test
    void rejectsMalformedJsonAsBadRequest(@TempDir Path tempDir) throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
             RuntimeApiServer server = new RuntimeApiServer(store, prompt -> "x", 0, "secret")) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            String threadId = createThread(client, base);

            HttpResponse<String> response = client.send(request(
                            base + "/v1/threads/" + threadId + "/turns", "POST", "{bad json").build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(400, response.statusCode());
            assertEquals("invalid_json", MAPPER.readTree(response.body()).path("error").asText());
        }
    }

    @Test
    void rejectsTurnsWhenTheBoundedQueueIsFull(@TempDir Path tempDir) throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
             RuntimeApiServer server = new RuntimeApiServer(store, prompt -> {
                 firstStarted.countDown();
                 release.await(5, TimeUnit.SECONDS);
                 return "done";
             }, 0, "secret", 1, 1)) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            String threadId = createThread(client, base);
            String url = base + "/v1/threads/" + threadId + "/turns";

            assertEquals(202, sendTurn(client, url, "one").statusCode());
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            assertEquals(202, sendTurn(client, url, "two").statusCode());
            HttpResponse<String> rejected = sendTurn(client, url, "three");

            assertEquals(429, rejected.statusCode());
            assertEquals("runtime_busy", MAPPER.readTree(rejected.body()).path("error").asText());
        } finally {
            release.countDown();
        }
    }

    private static String createThread(HttpClient client, String base) throws Exception {
        HttpResponse<String> created = client.send(request(base + "/v1/threads", "POST", "").build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, created.statusCode());
        return MAPPER.readTree(created.body()).path("id").asText();
    }

    private static HttpResponse<String> sendTurn(HttpClient client, String url, String input) throws Exception {
        String body = MAPPER.createObjectNode().put("input", input).toString();
        return client.send(request(url, "POST", body).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.Builder request(String url, String method, String body) {
        HttpRequest.BodyPublisher publisher = body == null || body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer secret")
                .header("Content-Type", "application/json")
                .method(method, publisher);
    }

    private static String waitForEvents(HttpClient client, String base, String threadId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = client.send(request(base + "/v1/threads/" + threadId + "/events", "GET", "")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.body().contains("turn.completed")) {
                return response.body();
            }
            Thread.sleep(30);
        }
        fail("events did not complete");
        return "";
    }

    private static String extract(String body, String prefix) {
        int start = body.indexOf(prefix);
        assertTrue(start >= 0, body);
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }
}
