package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CrossCaseBehaviorChecksTest {
    /** 正确小型 HTTP 产物可重复验收，不依赖编号从 1 开始。 */
    @Test void pollPassesTwice() throws Exception {
        var server = poll(false, false);
        try { assertEquals("PASS", pollCheck(server).status()); assertEquals(16, pollCheck(server).findings().stream().filter(f -> f.passed()).count()); }
        finally { server.stop(0); }
    }
    @Test void pollDetectsFakeVotingAndDuplicateMutation() throws Exception {
        var server = poll(true, false);
        try {
            var result = pollCheck(server);
            assertEquals("FAIL", result.status());
            assertTrue(result.findings().stream().anyMatch(f -> f.behavior().equals("poll.result.first") && !f.passed()));
        } finally { server.stop(0); }
    }
    @Test void pollBlocksUnusableCreation() throws Exception {
        var server = poll(false, true);
        try { assertEquals(15, pollCheck(server).findings().stream().filter(f -> f.status().equals("BLOCKED")).count()); }
        finally { server.stop(0); }
    }
    /** 路由根本不存在的404，不能当成资源不存在的正确处理。 */
    @Test void pollDoesNotCreditMissingVoteRouteAsResourceHandling() throws Exception {
        var server = poll(false, false, true);
        try {
            assertTrue(pollCheck(server).findings().stream().anyMatch(f -> f.behavior().equals("poll.missing.vote") && f.status().equals("BLOCKED")));
        } finally { server.stop(0); }
    }
    /** 原14项漏掉的身份撤销缺陷，正式判卷补强后也必须阻断。 */
    @Test void detectsIdentityRevokedByRejectedDuplicate() throws Exception {
        var server = poll(false, false, false, true);
        try {
            var full = pollCheck(server);
            assertEquals("FAIL", full.status());
            assertTrue(full.findings().stream().anyMatch(f -> f.behavior().equals("poll.vote.duplicate.repeat") && !f.passed()));
            var result = new PollIdentityRegressionProbe(base(server)).evaluate();
            assertEquals("FAIL", result.status());
            assertTrue(result.findings().stream().anyMatch(f -> f.behavior().equals("identity.repeat") && !f.passed()));
            assertTrue(result.findings().stream().anyMatch(f -> f.behavior().equals("identity.preserved") && !f.passed()));
        } finally { server.stop(0); }
    }
    @Test void repeatedDuplicatePreservesIdentityInCorrectFixture() throws Exception {
        var server = poll(false, false);
        try { assertEquals("PASS", new PollIdentityRegressionProbe(base(server)).evaluate().status()); }
        finally { server.stop(0); }
    }
    @Test void notePassesTwice() throws Exception {
        var server = note(false, false);
        try { assertEquals("PASS", noteCheck(server).status()); assertEquals(14, noteCheck(server).findings().stream().filter(f -> f.passed()).count()); }
        finally { server.stop(0); }
    }
    /** 返回删除重定向但未删除、直接输出用户 HTML，必须被独立检查捕获。 */
    @Test void noteDetectsFalseDeletionAndRawHtml() throws Exception {
        var server = note(true, false);
        try {
            var result = noteCheck(server);
            assertEquals("FAIL", result.status());
            assertTrue(result.findings().stream().anyMatch(f -> f.behavior().equals("note.deleted.detail") && !f.passed()));
            assertTrue(result.findings().stream().anyMatch(f -> f.behavior().equals("note.escaped") && !f.passed()));
        } finally { server.stop(0); }
    }
    @Test void noteBlocksWhenSubmissionFails() throws Exception {
        var server = note(false, true);
        try { assertEquals(8, noteCheck(server).findings().stream().filter(f -> f.status().equals("BLOCKED")).count()); }
        finally { server.stop(0); }
    }
    /** 保存产物出现未解析模板地址时，仍保留全部14项取证并将表单判为失败。 */
    @Test void malformedActionIsBusinessFailureInsteadOfEnvironmentError() throws Exception {
        var server = note(false, false, true);
        try {
            var result = noteCheck(server);
            assertEquals("FAIL", result.status());
            assertEquals(14, result.findings().size());
            assertTrue(result.findings().stream().anyMatch(f -> f.behavior().equals("note.delete.form") && !f.passed()));
        } finally { server.stop(0); }
    }
    private BenchmarkBehaviorResult pollCheck(HttpServer s) { return new PollWorkflowBehaviorChecks(base(s)).evaluate(); }
    private BenchmarkBehaviorResult noteCheck(HttpServer s) { return new NoteFormBehaviorChecks(base(s)).evaluate(); }
    private URI base(HttpServer s) { return URI.create("http://127.0.0.1:" + s.getAddress().getPort()); }

    /** 最小投票服务用于证明判卷能识别状态无变化，与真实生成源码分离。 */
    private HttpServer poll(boolean fake, boolean badCreate) throws Exception {
        return poll(fake, badCreate, false);
    }
    private HttpServer poll(boolean fake, boolean badCreate, boolean missingVotes) throws Exception {
        return poll(fake, badCreate, missingVotes, false);
    }
    private HttpServer poll(boolean fake, boolean badCreate, boolean missingVotes, boolean revokeIdentity) throws Exception {
        var mapper = new ObjectMapper();
        var counts = new HashMap<String, Map<String, Integer>>();
        var voters = new HashMap<String, Set<String>>();
        int[] ids = {40};
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/polls", e -> {
            String path = e.getRequestURI().getPath(); Object body = Map.of(); int status = 200;
            if (path.equals("/api/polls")) {
                String id = Integer.toString(++ids[0]); counts.put(id, new HashMap<>(Map.of("A", 0, "B", 0))); voters.put(id, new HashSet<>());
                status = 201; body = badCreate ? Map.of() : Map.of("id", id);
            } else {
                String id = path.split("/")[3];
                if (!counts.containsKey(id)) status = 404;
                else if (path.endsWith("/results")) body = Map.of("counts", counts.get(id));
                else if (missingVotes) status = 404;
                else {
                    var input = mapper.readTree(e.getRequestBody());
                    String voter = input.path("voterId").asText(""); String option = input.path("option").asText("");
                    if (input.path("voterId").isNull() || voter.isBlank() || !counts.get(id).containsKey(option)) status = 400;
                    else if (!voters.get(id).add(voter)) { status = 409; if (revokeIdentity) voters.get(id).remove(voter); }
                    else if (!fake) counts.get(id).compute(option, (k, v) -> v + 1);
                }
            }
            reply(e, status, mapper.writeValueAsString(body), "application/json");
        });
        server.start(); return server;
    }

    /** 模拟表单 HTTP 交互，错误模式故意保留已删除条目并输出原始正文。 */
    private HttpServer note(boolean fake, boolean badCreate) throws Exception {
        return note(fake, badCreate, false);
    }
    private HttpServer note(boolean fake, boolean badCreate, boolean badAction) throws Exception {
        var notes = new LinkedHashMap<Integer, Map<String, String>>(); int[] ids = {40};
        String fields = "<form method='post' action='/notes'><input name='title'><textarea name='body'></textarea></form>";
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/notes", e -> {
            String path = e.getRequestURI().getPath(), method = e.getRequestMethod(); int status = 200; String body = fields;
            if (path.equals("/notes") && method.equals("POST")) {
                Map<String, String> input = new HashMap<>();
                for (String pair : new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
                    var parts = pair.split("=", 2); input.put(parts[0], URLDecoder.decode(parts.length > 1 ? parts[1] : "", StandardCharsets.UTF_8));
                }
                if (input.getOrDefault("title", "").isBlank() || input.getOrDefault("body", "").isBlank()) status = 400;
                else if (badCreate) status = 500;
                else { notes.put(++ids[0], input); status = 302; e.getResponseHeaders().set("Location", "/notes"); }
            } else if (path.equals("/notes")) {
                var out = new StringBuilder("<a href='/notes/new'>New</a>");
                notes.forEach((id, value) -> out.append("<a href='/notes/").append(id).append("'>").append(escape(value.get("title"))).append("</a>"));
                body = out.toString();
            } else if (!path.equals("/notes/new")) {
                int id;
                try { id = Integer.parseInt(path.split("/")[2]); } catch (NumberFormatException ex) { id = -1; }
                var value = notes.get(id);
                if (value == null) { status = 404; body = "Missing"; }
                else if (method.equals("POST")) { status = 302; e.getResponseHeaders().set("Location", "/notes"); if (!fake) notes.remove(id); }
                else body = "<h1>" + escape(value.get("title")) + "</h1><p>" + (fake ? value.get("body") : escape(value.get("body")))
                        + "</p><form method='post' action='/notes/" + (badAction ? "${note.id}" : id) + "/delete'></form>";
            }
            reply(e, status, "<html><body>" + body + "</body></html>", "text/html; charset=UTF-8");
        });
        server.start(); return server;
    }
    private String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private void reply(HttpExchange e, int status, String body, String type) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type", type);
        e.sendResponseHeaders(status, bytes.length); e.getResponseBody().write(bytes); e.close();
    }
}
