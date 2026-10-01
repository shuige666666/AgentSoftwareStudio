package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorFinding;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 固定任务 API 的外部判卷，检查真实状态变化，不参与生成策略或任务拆分。 */
final class TaskBehaviorChecks {
    private final URI base;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<BenchmarkBehaviorFinding> findings = new ArrayList<>();
    private record Reply(int status, String body, String request) { }
    TaskBehaviorChecks(URI base) { this.base = base; }

    /** 从创建响应获取 ID，验证完成与删除的持久效果及已有能力回归。 */
    BenchmarkBehaviorResult evaluate() {
        Reply created = request("POST", "/api/tasks", "{\"title\":\"Acceptance task\"}");
        JsonNode task = json(created);
        String id = task.path("id").asText("");
        boolean usable = created.status == 201 && !id.isBlank() && task.path("id").canConvertToLong()
                && "Acceptance task".equals(task.path("title").asText())
                && task.path("completed").isBoolean() && !task.path("completed").asBoolean();
        check("task.create", created, usable, "201; JSON id 为整数、title 原值、completed=false");
        Reply beforeInvalid = request("GET", "/api/tasks", null);
        Reply invalid = request("POST", "/api/tasks", "{\"title\":\" \"}");
        Reply nullTitle = request("POST", "/api/tasks", "{\"title\":null}");
        Reply longTitle = request("POST", "/api/tasks", "{\"title\":\"" + "x".repeat(101) + "\"}");
        Reply afterInvalid = request("GET", "/api/tasks", null);
        check("task.invalid.create", new Reply(invalid.status, invalid.body,
                invalid.request + "; " + nullTitle.request + "; " + longTitle.request),
                invalid.status == 400 && nullTitle.status == 400 && longTitle.status == 400 && beforeInvalid.status == 200
                && afterInvalid.status == 200 && json(beforeInvalid).isArray() && json(beforeInvalid).equals(json(afterInvalid)),
                "空白、null、超过100字符的标题均为400；非法创建后列表状态不变",
                "nullTitle=" + evidence(nullTitle) + "; longTitle=" + evidence(longTitle)
                        + "; before=" + evidence(beforeInvalid) + "; after=" + evidence(afterInvalid));
        check("task.missing.query", request("GET", "/api/tasks/9223372036854775807", null), 404);
        if (!usable) {
            for (String name : List.of("task.query", "task.list", "task.complete", "task.complete.persisted",
                    "task.complete.idempotent", "task.missing.complete", "task.delete", "task.delete.absent",
                    "task.missing.delete")) blocked(name, "task.create");
            return BenchmarkBehaviorResult.fromFindings(findings);
        }
        String path = "/api/tasks/" + id;
        Reply queried = request("GET", path, null);
        check("task.query", queried, queried.status == 200 && json(queried).equals(task), "200; 创建结果保持一致");
        Reply listed = request("GET", "/api/tasks", null);
        boolean visible = false;
        if (json(listed).isArray()) for (JsonNode item : json(listed)) if (item.equals(task)) visible = true;
        check("task.list", listed, listed.status == 200 && visible, "200; JSON 数组包含创建的任务");
        Reply completed = request("POST", path + "/complete", null);
        boolean done = completed.status == 200 && completedTask(json(completed), task);
        check("task.complete", completed, done, "200; 原 id/title 保留且 completed=true");
        if (done) {
            Reply persisted = request("GET", path, null);
            check("task.complete.persisted", persisted, persisted.status == 200 && completedTask(json(persisted), task),
                    "200; 查询仍为 completed=true");
            Reply beforeRepeat = request("GET", "/api/tasks", null);
            Reply repeated = request("POST", path + "/complete", null);
            Reply afterRepeat = request("GET", "/api/tasks", null);
            check("task.complete.idempotent", repeated, repeated.status == 200 && completedTask(json(repeated), task)
                    && beforeRepeat.status == 200 && afterRepeat.status == 200 && json(beforeRepeat).isArray()
                    && json(beforeRepeat).equals(json(afterRepeat)), "200; 重复完成仍返回相同任务且列表状态不变",
                    "before=" + evidence(beforeRepeat) + "; after=" + evidence(afterRepeat));
        } else { blocked("task.complete.persisted", "task.complete"); blocked("task.complete.idempotent", "task.complete"); }
        check("task.missing.complete", request("POST", "/api/tasks/9223372036854775807/complete", null), 404);
        Reply deleted = request("DELETE", path, null);
        check("task.delete", deleted, 204);
        if (deleted.status == 204) {
            Reply absent = request("GET", path, null);
            Reply remaining = request("GET", "/api/tasks", null);
            boolean stillListed = false;
            for (JsonNode item : json(remaining)) if (id.equals(item.path("id").asText())) stillListed = true;
            check("task.delete.absent", absent, absent.status == 404 && remaining.status == 200
                    && json(remaining).isArray() && !stillListed, "404; 已删除任务不可查询且不再出现在列表",
                    "remaining=" + evidence(remaining));
        } else blocked("task.delete.absent", "task.delete");
        check("task.missing.delete", request("DELETE", "/api/tasks/9223372036854775807", null), 404);
        return BenchmarkBehaviorResult.fromFindings(findings);
    }

    /** 完成动作必须保留身份和标题，避免只返回一份伪造的成功响应。 */
    private boolean completedTask(JsonNode actual, JsonNode original) {
        return actual.path("id").equals(original.path("id")) && actual.path("title").equals(original.path("title"))
                && actual.path("completed").isBoolean() && actual.path("completed").asBoolean();
    }

    /** 无正文 POST 与 DELETE 仍使用真实方法；请求故障作为失败证据。 */
    private Reply request(String method, String path, String body) {
        try {
            var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json").method(method, body == null
                            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            if (body != null) builder.header("Content-Type", "application/json");
            var reply = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(reply.statusCode(), reply.body(), method + " " + path + (body == null ? "" : " " + body));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(-1, "HTTP request interrupted", method + " " + path);
        } catch (Exception e) { return new Reply(-1, e.getClass().getSimpleName() + ": " + e.getMessage(), method + " " + path); }
    }

    /** 非 JSON 或空响应保留 HTTP 证据，并使结构断言失败。 */
    private JsonNode json(Reply reply) {
        try { JsonNode value = mapper.readTree(reply.body); return value == null ? mapper.createObjectNode() : value; }
        catch (Exception e) { return mapper.createObjectNode(); }
    }
    /** 将主请求及关联查询一起记录，便于定位状态码正确但状态未改变的问题。 */
    private void check(String name, Reply reply, int expected) { check(name, reply, reply.status == expected, expected + "; HTTP 状态码"); }
    private void check(String name, Reply reply, boolean passed, String expected) {
        check(name, reply, passed, expected, "");
    }
    private void check(String name, Reply reply, boolean passed, String expected, String related) {
        findings.add(new BenchmarkBehaviorFinding(name, passed, evidence(reply) + (related.isBlank() ? "" : "; " + related),
                passed ? "PASS" : "FAIL", reply.request, expected));
    }
    private String evidence(Reply reply) {
        String body = reply.body.length() <= 400 ? reply.body : reply.body.substring(0, 400) + " [body omitted]";
        return "HTTP " + reply.status + "; body=" + body;
    }
    /** 前置动作失败时标为未执行，避免将缺失资源的响应误认作业务成功。 */
    private void blocked(String name, String dependency) {
        findings.add(new BenchmarkBehaviorFinding(name, false, "Blocked by " + dependency, "BLOCKED", "未执行", dependency + " 必须通过"));
    }
}
