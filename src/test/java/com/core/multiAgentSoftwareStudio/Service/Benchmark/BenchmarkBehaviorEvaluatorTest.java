package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BenchmarkBehaviorEvaluatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final BenchmarkBehaviorEvaluator evaluator =
            new BenchmarkBehaviorEvaluator(mock(DockerSandboxService.class), mapper);

    /**
     * 固定短链接产物重复判卷应得到相同的行为结论。
     */
    @Test
    void judgesShortLinkFixtureRepeatedly() throws Exception {
        HttpServer server = shortLinkFixture();
        try {
            URI base = base(server);
            var first = evaluator.evaluateAt("url-shortener-behavior-v1", base);
            var second = evaluator.evaluateAt("url-shortener-behavior-v1", base);

            assertEquals("PASS", first.status());
            assertEquals(first.findings(), second.findings());
            assertEquals(5, first.findings().size());
        } finally {
            server.stop(0);
        }
    }

    /**
     * 重复投票即使返回成功，也必须因票数改变被独立判卷发现。
     */
    @Test
    void catchesDuplicateVoteInBrokenFixture() throws Exception {
        HttpServer correct = pollFixture(false);
        HttpServer broken = pollFixture(true);
        try {
            var first = evaluator.evaluateAt("poll-system-behavior-v1", base(correct));
            var repeated = evaluator.evaluateAt("poll-system-behavior-v1", base(correct));
            var wrong = evaluator.evaluateAt("poll-system-behavior-v1", base(broken));

            assertEquals("PASS", first.status());
            assertEquals(first.findings(), repeated.findings());
            assertEquals("FAIL", wrong.status());
            assertTrue(wrong.findings().stream().anyMatch(f ->
                    f.behavior().equals("poll.vote.duplicate") && !f.passed()));
            assertTrue(wrong.findings().stream().anyMatch(f ->
                    f.behavior().equals("poll.result.afterDuplicate") && !f.passed()));
        } finally {
            correct.stop(0);
            broken.stop(0);
        }
    }

    private HttpServer shortLinkFixture() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, String> links = new HashMap<>();
        server.createContext("/api/links", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api/links") && exchange.getRequestMethod().equals("POST")) {
                JsonNode input = mapper.readTree(exchange.getRequestBody());
                String url = input.path("url").asText();
                links.put("abc", url);
                respond(exchange, 201, "{\"code\":\"abc\",\"originalUrl\":\"" + url + "\"}");
            } else {
                String code = path.substring("/api/links/".length());
                String url = links.get(code);
                respond(exchange, url == null ? 404 : 200,
                        url == null ? "{}" : "{\"code\":\"abc\",\"originalUrl\":\"" + url + "\"}");
            }
        });
        server.createContext("/", exchange -> {
            String code = exchange.getRequestURI().getPath().substring(1);
            String url = links.get(code);
            if (url != null) {
                exchange.getResponseHeaders().add("Location", url);
            }
            respond(exchange, url == null ? 404 : 302, "");
        });
        server.start();
        return server;
    }

    private HttpServer pollFixture(boolean allowDuplicate) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, Integer> counts = new HashMap<>(Map.of("A", 0, "B", 0));
        Set<String> voters = new HashSet<>();
        server.createContext("/api/polls", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api/polls") && exchange.getRequestMethod().equals("POST")) {
                counts.put("A", 0);
                counts.put("B", 0);
                voters.clear();
                respond(exchange, 201, "{\"id\":\"poll-1\"}");
            } else if (path.equals("/api/polls/poll-1/results")) {
                respond(exchange, 200, "{\"counts\":{\"A\":" + counts.get("A")
                        + ",\"B\":" + counts.get("B") + "}}");
            } else if (path.equals("/api/polls/poll-1/votes")) {
                JsonNode input = mapper.readTree(exchange.getRequestBody());
                String voter = input.path("voterId").asText();
                String option = input.path("option").asText();
                if (!allowDuplicate && !voters.add(voter)) {
                    respond(exchange, 409, "{}");
                } else {
                    counts.merge(option, 1, Integer::sum);
                    respond(exchange, 200, "{}");
                }
            } else {
                respond(exchange, 404, "{}");
            }
        });
        server.start();
        return server;
    }

    private URI base(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        if (status == 302) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
