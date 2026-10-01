package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.core.multiAgentSoftwareStudio.Model.Benchmark.*;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/** 小型固定案例共用 HTTP 取证，不参与生成策略或任务拆分。 */
abstract class FixedHttpChecks {
    protected final URI base;
    protected final List<BenchmarkBehaviorFinding> findings = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    protected record Reply(int status, String body, String location, String request) { }
    FixedHttpChecks(URI base) { this.base = base; }
    abstract BenchmarkBehaviorResult evaluate();

    /** 保留原方法和响应状态；正文为空的 POST 仍是 POST。 */
    protected Reply request(String method, String path, String body, String contentType) {
        String description = method + " " + path + (body == null ? "" : "; body=" + body);
        try {
            var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10));
            if (contentType != null) builder.header("Content-Type", contentType);
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Reply(response.statusCode(), response.body(), response.headers().firstValue("location").orElse(""), description);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(0, "HTTP 请求被中断", "", description);
        } catch (Exception e) {
            return new Reply(0, e.getClass().getSimpleName() + ": " + e.getMessage(), "", description);
        }
    }

    /** 表单按标准编码发送，包含空字段以验证真实边界。 */
    protected Reply form(String method, String path, Map<String, String> fields) {
        String body = fields.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e ->
                URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return request(method, path, method.equals("POST") ? body : null, method.equals("POST") ? "application/x-www-form-urlencoded" : null);
    }
    protected Reply jsonRequest(String method, String path, String body) { return request(method, path, body, "application/json"); }
    protected JsonNode json(Reply reply) {
        try { var node = mapper.readTree(reply.body()); return node == null ? mapper.createObjectNode() : node; }
        catch (Exception ignored) { return mapper.createObjectNode(); }
    }
    protected boolean redirect(Reply reply, String path) {
        return (reply.status() == 302 || reply.status() == 303) && !reply.location().isBlank()
                && matchesPath(reply.location(), path);
    }
    /** 候选输出的地址可能含未解析模板字符；应判业务失败，不能变成判卷环境异常。 */
    protected boolean matchesPath(String reference, String path) {
        try { return path.equals(base.resolve(reference).getPath()); }
        catch (IllegalArgumentException e) { return false; }
    }
    protected void check(String name, Reply reply, boolean passed, String expected) {
        findings.add(new BenchmarkBehaviorFinding(name, passed, evidence(reply), passed ? "PASS" : "FAIL", reply.request(), expected));
    }
    protected void blocked(String name, String dependency) {
        findings.add(new BenchmarkBehaviorFinding(name, false, "依赖 " + dependency + " 未通过", "BLOCKED", "", ""));
    }
    protected String evidence(Reply reply) {
        String body = reply.body().replaceAll("\\s+", " ");
        return "HTTP " + reply.status() + "; Location=" + reply.location() + "; body=" + body.substring(0, Math.min(body.length(), 400));
    }
}
