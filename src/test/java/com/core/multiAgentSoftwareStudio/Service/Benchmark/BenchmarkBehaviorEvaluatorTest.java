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
    /** 请求期500保留真实异常和完整日志，容器清理前必须完成取证。 */
    @Test
    void preservesRequestTimeFailureWithoutChangingFindings(@org.junit.jupiter.api.io.TempDir java.nio.file.Path project) throws Exception {
        var sandbox = mock(DockerSandboxService.class);
        org.mockito.Mockito.when(sandbox.isDockerAvailable()).thenReturn(true);
        org.mockito.Mockito.when(sandbox.startSpringBootPreview(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn("container");
        org.mockito.Mockito.when(sandbox.isContainerRunning("container")).thenReturn(true);
        String logs = "Started Application in 1 seconds\n" + "[INFO] banner\n".repeat(100)
                + "org.thymeleaf.exceptions.TemplateInputException: Error resolving template [literal-html]\n";
        org.mockito.Mockito.when(sandbox.getContainerLogs("container")).thenReturn("Started Application in 1 seconds", logs);
        var failed = new com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult("FAIL", java.util.List.of(
                new com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorFinding("missing", false, "HTTP 500; body={}")),
                "Original case diagnostic");
        var tested = new BenchmarkBehaviorEvaluator(sandbox, new ObjectMapper()) {
            @Override com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult evaluateAt(String caseId, URI uri) { return failed; }
        };
        org.mockito.Mockito.doAnswer(invocation -> {
            try (var files = java.nio.file.Files.list(project.resolve("target"))) {
                assertEquals(logs, java.nio.file.Files.readString(files.findFirst().orElseThrow()));
            }
            return null;
        }).when(sandbox).stopAndRemoveContainer("container");
        var result = tested.evaluate("url-shortener-behavior-v1", project.toString());
        assertEquals("FAIL", result.status());
        assertEquals(failed.findings(), result.findings());
        assertTrue(result.reason().contains("TemplateInputException"));
        assertTrue(result.reason().contains("literal-html"));
        assertTrue(result.reason().contains("Original case diagnostic"));
        org.mockito.Mockito.verify(sandbox).stopAndRemoveContainer("container");
    }

    /** 日志读取失败仍保留原HTTP失败；正常通过的判卷不增加日志请求或磁盘取证。 */
    @Test
    void logFailureDoesNotReplaceHttpFailureAndPassingChecksSkipCapture(@org.junit.jupiter.api.io.TempDir java.nio.file.Path project) {
        var sandbox = mock(DockerSandboxService.class);
        org.mockito.Mockito.when(sandbox.isDockerAvailable()).thenReturn(true);
        org.mockito.Mockito.when(sandbox.startSpringBootPreview(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn("container");
        org.mockito.Mockito.when(sandbox.isContainerRunning("container")).thenReturn(true);
        org.mockito.Mockito.when(sandbox.getContainerLogs("container")).thenReturn("Started Application in 1 seconds").thenThrow(new IllegalStateException("log unavailable"));
        var findings = java.util.List.of(new com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorFinding("create", false, "HTTP 503"));
        var tested = new BenchmarkBehaviorEvaluator(sandbox, new ObjectMapper()) {
            @Override com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult evaluateAt(String caseId, URI uri) {
                return com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult.fromFindings(findings);
            }
        };
        var result = tested.evaluate("url-shortener-behavior-v1", project.toString());
        assertEquals("FAIL", result.status());
        assertEquals(findings, result.findings());
        assertTrue(result.reason().contains("log unavailable"));
        org.mockito.Mockito.verify(sandbox).stopAndRemoveContainer("container");
        org.mockito.Mockito.reset(sandbox);
        org.mockito.Mockito.when(sandbox.isDockerAvailable()).thenReturn(true);
        org.mockito.Mockito.when(sandbox.startSpringBootPreview(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn("passed-container");
        org.mockito.Mockito.when(sandbox.isContainerRunning("passed-container")).thenReturn(true);
        org.mockito.Mockito.when(sandbox.getContainerLogs("passed-container")).thenReturn("Started Application in 1 seconds");
        var passed = new BenchmarkBehaviorEvaluator(sandbox, new ObjectMapper()) {
            @Override com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult evaluateAt(String caseId, URI uri) {
                return com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult.fromFindings(java.util.List.of());
            }
        };
        assertTrue(passed.evaluate("url-shortener-behavior-v1", project.toString()).passed());
        org.mockito.Mockito.verify(sandbox, org.mockito.Mockito.times(1)).getContainerLogs("passed-container");
        assertFalse(java.nio.file.Files.exists(project.resolve("target")));
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final BenchmarkBehaviorEvaluator evaluator =
            new BenchmarkBehaviorEvaluator(mock(DockerSandboxService.class), mapper);

    /** 启动失败分析在长 Maven 日志之后也必须保留需要注册的 Bean，完整日志在容器清理前落盘。 */
    @Test
    void preservesStartupFailureAndFullLogsBeforeRemovingContainer(@org.junit.jupiter.api.io.TempDir java.nio.file.Path project) throws Exception {
        String logs = "[INFO] Maven startup banner\n".repeat(30)
                + "APPLICATION FAILED TO START\nDescription:\nParameter 0 of constructor in demo.Controller "
                + "required a bean of type 'demo.Service' that could not be found.\nAction:\nDefine the bean.\n";
        var sandbox = mock(DockerSandboxService.class);
        org.mockito.Mockito.when(sandbox.isDockerAvailable()).thenReturn(true);
        org.mockito.Mockito.when(sandbox.startSpringBootPreview(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn("failed-container");
        org.mockito.Mockito.when(sandbox.isContainerRunning("failed-container")).thenReturn(false);
        org.mockito.Mockito.when(sandbox.getContainerLogs("failed-container")).thenReturn(logs);
        var result = new BenchmarkBehaviorEvaluator(sandbox, mapper).evaluate("url-shortener-behavior-v1", project.toString());
        assertEquals("NOT_RUN", result.status());
        assertTrue(result.reason().contains("demo.Service"));
        assertTrue(result.reason().contains("required a bean"));
        try (var files = java.nio.file.Files.list(project.resolve("target"))) {
            var evidence = files.findFirst().orElseThrow();
            assertEquals(logs, java.nio.file.Files.readString(evidence));
            assertTrue(result.reason().contains(evidence.toString()));
        }
        org.mockito.Mockito.verify(sandbox).stopAndRemoveContainer("failed-container");
    }

    /** 非 Spring 异常展示最底层原因；普通日志仍有界保留首尾。 */
    @Test
    void keepsUnderlyingStartupCauseAndBoundsFallbackLog() {
        String logs = "[INFO] Banner\n".repeat(100)
                + "Caused by: java.lang.IllegalStateException: wrapper\n"
                + "Caused by: java.lang.ClassNotFoundException: demo.MissingDriver\n";
        assertTrue(BenchmarkBehaviorEvaluator.startupFailureSummary(logs).contains("demo.MissingDriver"));
        assertTrue(BenchmarkBehaviorEvaluator.startupFailureSummary("info ".repeat(1000) + "last startup detail")
                .contains("last startup detail"));
        assertTrue(BenchmarkBehaviorEvaluator.startupFailureSummary(logs).length() <= 1203);
    }

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

    /** 创建响应不符合契约时，依赖该资源的检查标为阻塞并交付可操作预期。 */
    @Test
    void marksDependentChecksBlockedWhenCreateFails() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange,
                exchange.getRequestMethod().equals("POST") ? 200 : 404, "{}"));
        server.start();
        try {
            var result = evaluator.evaluateAt("url-shortener-behavior-v1", base(server));
            assertEquals("FAIL", result.status());
            assertEquals(2, result.findings().stream().filter(f -> "BLOCKED".equals(f.status())).count());
            var created = result.findings().getFirst();
            assertTrue(created.request().contains("POST /api/links"));
            assertTrue(created.expected().contains("201"));
            assertTrue(created.expected().contains("https://example.org/docs"));
            assertTrue(created.evidence().contains("HTTP 200"));
        } finally {
            server.stop(0);
        }
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
