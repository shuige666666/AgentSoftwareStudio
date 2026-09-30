package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlogBehaviorChecksTest {
    /** 正确表单闭环全部通过；已观察到的错误 200 必须产生九项失败，不能被重定向吞掉。 */
    @Test
    void checksWorkingBlogAndRejectsWrongErrorStatuses() throws Exception {
        HttpServer correct = fixture(false);
        HttpServer wrong = fixture(true);
        try {
            var passed = new BlogBehaviorChecks(base(correct)).evaluate();
            var failed = new BlogBehaviorChecks(base(wrong)).evaluate();
            assertEquals(24, passed.findings().size());
            assertEquals("PASS", passed.status());
            assertEquals("FAIL", failed.status());
            assertEquals(9, failed.findings().stream().filter(f -> !f.passed()).count());
            var invalid = failed.findings().stream().filter(f -> f.behavior().equals("invalid-create-400")).findFirst().orElseThrow();
            assertTrue(invalid.request().contains("title=bad"));
            assertTrue(invalid.expected().contains("400"));
            assertTrue(invalid.evidence().contains("HTTP 200"));
        } finally {
            correct.stop(0);
            wrong.stop(0);
        }
    }

    /** 没有创建能力时，后续 404 不应构成删除正确的证据。 */
    @Test
    void blocksDependentChecksWithoutAnArticle() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange, 404, "<html></html>"));
        server.start();
        try {
            var result = new BlogBehaviorChecks(base(server)).evaluate();
            assertEquals(24, result.findings().size());
            assertEquals("FAIL", result.status());
            assertTrue(result.findings().stream().anyMatch(f -> f.behavior().equals("delete-effective")
                    && f.status().equals("BLOCKED")));
            assertTrue(result.findings().stream().filter(f -> f.behavior().startsWith("missing-")).allMatch(f -> !f.passed()));
        } finally {
            server.stop(0);
        }
    }

    /** 小型固定 HTTP 产物覆盖语义和状态码，与生成代码及模型调用无关。 */
    private HttpServer fixture(boolean wrongErrors) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, String> state = new HashMap<>();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            boolean post = exchange.getRequestMethod().equals("POST");
            Map<String, String> form = new HashMap<>();
            if (post) {
                for (String field : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
                    String[] pair = field.split("=", 2);
                    if (pair.length == 2) form.put(decode(pair[0]), decode(pair[1]));
                }
            }
            String fields = "<input name='title'><textarea name='content'></textarea>";
            if (path.equals("/articles/new")) { respond(exchange, 200, fields); return; }
            if (post && !path.endsWith("/delete") && (form.getOrDefault("content", "").isBlank()
                    || path.endsWith("/comments") && form.getOrDefault("nickname", "").length() < 2)) {
                respond(exchange, wrongErrors ? 200 : 400, fields); return;
            }
            if (path.equals("/articles") && post) {
                state.put("title", form.get("title")); state.put("content", form.get("content"));
                redirect(exchange, "/articles"); return;
            }
            if (path.equals("/articles")) {
                String query = exchange.getRequestURI().getQuery();
                boolean visible = state.containsKey("title") && (query == null || query.contains("UniqueBetaContent"));
                respond(exchange, 200, "<html>" + (visible ? "<a href='/articles/41'>" + state.get("title") + "</a>" : "") + "</html>"); return;
            }
            if (path.startsWith("/articles/41") && state.containsKey("title")) {
                if (post) {
                    if (path.endsWith("/delete")) { state.clear(); redirect(exchange, "/articles"); return; }
                    if (path.endsWith("/comments")) state.put("comment", form.get("nickname") + form.get("content"));
                    else { state.put("title", form.get("title")); state.put("content", form.get("content")); }
                    redirect(exchange, "/articles/41"); return;
                }
                respond(exchange, 200, "<html>" + state.get("title") + state.get("content") + state.getOrDefault("comment", "")
                        + "<form action='/articles/41/comments'><input name='nickname'><textarea name='content'></textarea></form></html>"); return;
            }
            respond(exchange, wrongErrors ? 200 : 404, "missing");
        });
        server.start();
        return server;
    }

    private URI base(HttpServer server) { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
    private String decode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
    private void redirect(HttpExchange exchange, String location) throws java.io.IOException {
        exchange.getResponseHeaders().add("Location", location + ";jsessionid=test");
        respond(exchange, 302, "");
    }
    private void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
