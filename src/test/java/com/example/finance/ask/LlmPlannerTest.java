package com.example.finance.ask;

import com.example.finance.Fixtures;
import com.example.finance.util.Json;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the planner against a local fake of an OpenAI-compatible endpoint. */
class LlmPlannerTest {
    private static final DatasetContext CTX = DatasetContext.of(Fixtures.SMALL);

    private interface Check {
        void run(String baseUrl, AtomicReference<String> request) throws Exception;
    }

    private static void withFakeModel(int status, String content, Check check) throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            request.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = Json.write(Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant", "content", content)))))
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, body.length);
            try (var out = ex.getResponseBody()) {
                out.write(body);
            } catch (IOException ignored) {
                // client went away
            }
        });
        server.start();
        try {
            check.run("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/", request);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsJsonModeAndParsesTheQuery() throws Exception {
        withFakeModel(200, "{\"metric\":\"sum\",\"type\":\"DEBIT\",\"categories\":[\"Groceries\"],\"from\":\"2025-01-01\",\"to\":\"2025-01-31\"}",
                (url, request) -> {
                    QuerySpec s = new LlmPlanner(url, "test-key", "test-model", "low").plan("Groceries in January?", CTX);
                    assertEquals(List.of("Groceries"), s.categories());
                    Map<String, Object> sent = Json.parseObject(request.get());
                    assertEquals("test-model", sent.get("model"));
                    assertEquals(Map.of("type", "json_object"), sent.get("response_format"));
                    assertEquals("low", sent.get("reasoning_effort"));
                    assertTrue(String.valueOf(sent.get("messages")).contains("Groceries"), "the prompt lists the dataset's categories");
                });
    }

    @Test
    void toleratesCodeFencesAroundTheJson() throws Exception {
        withFakeModel(200, "```json\n{\"metric\":\"count\"}\n```", (url, request) ->
                assertEquals(QuerySpec.Metric.COUNT, new LlmPlanner(url, "k", "m", "").plan("How many?", CTX).metric()));
    }

    @Test
    void rejectsACategoryTheDataDoesNotHave() throws Exception {
        withFakeModel(200, "{\"metric\":\"sum\",\"categories\":[\"Yachts\"]}", (url, request) ->
                assertThrows(IllegalArgumentException.class, () -> new LlmPlanner(url, "k", "m", "").plan("Yachts?", CTX)));
    }

    @Test
    void reportsHttpErrors() throws Exception {
        withFakeModel(429, "rate limited", (url, request) ->
                assertThrows(IllegalStateException.class, () -> new LlmPlanner(url, "k", "m", "").plan("Anything", CTX)));
    }
}
