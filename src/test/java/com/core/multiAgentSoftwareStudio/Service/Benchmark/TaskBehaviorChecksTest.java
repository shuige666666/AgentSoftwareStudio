package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TaskBehaviorChecksTest {
    /** 正确产物可重复判卷；不能假设存储为空或 ID 从 1 开始。 */
    @Test void passesCorrectFixtureTwice() throws Exception {
        var server = fixture(false, false, false);
        try {
            assertEquals("PASS", check(server).status());
            assertEquals("PASS", check(server).status());
            assertEquals(12, check(server).findings().size());
        } finally { server.stop(0); }
    }
    /** 成功响应不等于实际完成或删除，独立读取应发现伪造成功。 */
    @Test void rejectsSuccessWithoutStateChange() throws Exception {
        var server = fixture(true, true, false);
        try {
            var result = check(server);
            assertEquals("FAIL", result.status());
            assertTrue(result.findings().stream().anyMatch(f -> f.behavior().equals("task.complete.persisted") && !f.passed()));
            assertTrue(result.findings().stream().anyMatch(f -> f.behavior().equals("task.delete.absent") && !f.passed()));
        } finally { server.stop(0); }
    }
    /** 前置创建失败时，后续能力明确阻塞，不把空状态的 404 当成删除成功。 */
    @Test void blocksWhenCreationFails() throws Exception {
        var server = fixture(false, false, true);
        try {
            var result = check(server);
            assertEquals(12, result.findings().size());
            assertTrue(result.findings().stream().anyMatch(f -> f.behavior().equals("task.delete.absent") && f.status().equals("BLOCKED")));
        } finally { server.stop(0); }
    }
    private com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult check(HttpServer server) {
        return new TaskBehaviorChecks(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).evaluate();
    }
    /** 用最小 HTTP 产物检验外部判卷，而非重复生成代码的实现断言。 */
    private HttpServer fixture(boolean fakeComplete, boolean fakeDelete, boolean badCreate) throws Exception {
        var mapper = new ObjectMapper();
        var tasks = new LinkedHashMap<Long, Map<String, Object>>();
        long[] sequence = {40};
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tasks", exchange -> {
            String method = exchange.getRequestMethod(), path = exchange.getRequestURI().getPath();
            Object body = Map.of(); int status = 200;
            if (path.equals("/api/tasks")) {
                if (method.equals("GET")) body = tasks.values();
                else {
                    var titleNode = mapper.readTree(exchange.getRequestBody()).path("title");
                    String title = titleNode.asText();
                    if (titleNode.isNull() || title.isBlank() || title.length() > 100) status = 400;
                    else {
                        long id = ++sequence[0];
                        var value = Map.<String, Object>of("id", id, "title", title, "completed", false);
                        tasks.put(id, value); body = badCreate ? Map.of() : value; status = 201;
                    }
                }
            } else {
                long id = Long.parseLong(path.split("/")[3]); var value = tasks.get(id);
                if (value == null) status = 404;
                else if (method.equals("GET")) body = value;
                else if (method.equals("POST")) {
                    var done = new LinkedHashMap<>(value); done.put("completed", true); body = done;
                    if (!fakeComplete) tasks.put(id, done);
                } else { status = 204; if (!fakeDelete) tasks.remove(id); }
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = mapper.writeValueAsBytes(body);
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start(); return server;
    }
}
