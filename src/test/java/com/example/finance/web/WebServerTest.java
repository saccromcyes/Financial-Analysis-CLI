package com.example.finance.web;

import com.example.finance.ask.AskService;
import com.example.finance.io.FinancialCsvReader;
import com.example.finance.util.Json;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Starts the real server on a free port and talks to it over HTTP. */
class WebServerTest {
    private final HttpClient http = HttpClient.newHttpClient();

    private interface Check {
        void run(String base) throws Exception;
    }

    private static void withServer(Check check) throws Exception {
        WebServer web = new WebServer(FinancialCsvReader.readTransactions(null), new AskService(null, "$"), "$");
        HttpServer server = web.start(0);
        try {
            check.run("http://127.0.0.1:" + server.getAddress().getPort());
        } finally {
            server.stop(0);
        }
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String url, String type, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", type)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthAndPage() throws Exception {
        withServer(base -> {
            assertEquals(200, get(base + "/health").statusCode());
            HttpResponse<String> page = get(base + "/");
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("<title>Tally"));
            assertEquals("nosniff", page.headers().firstValue("X-Content-Type-Options").orElse(""));
        });
    }

    @Test
    void answersAQuestion() throws Exception {
        withServer(base -> {
            HttpResponse<String> r = post(base + "/api/ask", "application/json",
                    "{\"question\": \"How much did I spend on groceries in March?\", \"dataset\": \"sample\"}");
            assertEquals(200, r.statusCode(), r.body());
            Map<String, Object> body = Json.parseObject(r.body());
            assertEquals("rules", body.get("planned_by"));
            assertTrue(String.valueOf(body.get("answer")).startsWith("You spent $"));
        });
    }

    @Test
    void rejectsBadQuestions() throws Exception {
        withServer(base -> {
            assertEquals(400, post(base + "/api/ask", "application/json", "not json").statusCode());
            assertEquals(422, post(base + "/api/ask", "application/json", "{\"question\": \"?\"}").statusCode());
            assertEquals(422, post(base + "/api/ask", "application/json", "{\"question\": \"" + "a".repeat(301) + "\"}").statusCode());
        });
    }

    @Test
    void uploadThenAskAboutTheUpload() throws Exception {
        withServer(base -> {
            String csv = "date,description,category,type,amount,balance,destination\n"
                    + "2025-02-01,Coffee,Food & Dining,DEBIT,4.50,100,Bean There\n"
                    + "2025-02-02,Coffee,Food & Dining,DEBIT,5.50,95.5,Bean There\n";
            HttpResponse<String> up = post(base + "/api/datasets", "text/csv", csv);
            assertEquals(200, up.statusCode(), up.body());
            String id = String.valueOf(Json.parseObject(up.body()).get("id"));
            assertTrue(id.matches("[a-f0-9]{12}"), id);
            HttpResponse<String> r = post(base + "/api/ask", "application/json", "{\"question\": \"How much did I spend?\", \"dataset\": \"" + id + "\"}");
            assertEquals(10.0, ((Number) Json.parseObject(r.body()).get("value")).doubleValue(), 0.001);
            assertEquals(200, get(base + "/api/summary?d=" + id).statusCode());
        });
    }

    @Test
    void unknownDatasetsBadUploadsAndPathTricksAreRefused() throws Exception {
        withServer(base -> {
            assertEquals(404, get(base + "/api/summary?d=ffffffffffff").statusCode());
            assertEquals(404, get(base + "/api/summary?d=../../etc").statusCode());
            assertEquals(400, post(base + "/api/datasets", "text/csv", "name,age\nbob,4\n").statusCode());
            assertEquals(413, post(base + "/api/datasets", "text/csv", "x".repeat(1_000_001)).statusCode());
            assertEquals(404, get(base + "/..%2f..%2fpom.xml").statusCode());
            assertEquals(404, get(base + "/sample/financial_transactions.csv").statusCode());
            assertEquals(405, http.send(HttpRequest.newBuilder(URI.create(base + "/health")).DELETE().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
        });
    }
}
