package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorFinding;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 从生成项目外部执行版本化 HTTP 契约，避免候选代码修改判卷规则。
 */
@Service
public class BenchmarkBehaviorEvaluator {
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);
    private static final Pattern STARTED = Pattern.compile("\\bStarted\\s+[A-Za-z0-9_.$]+\\s+in\\s+\\d");
    private final DockerSandboxService sandbox;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public BenchmarkBehaviorEvaluator(DockerSandboxService sandbox, ObjectMapper mapper) {
        this.sandbox = sandbox;
        this.mapper = mapper;
    }

    /**
     * 仅新版两个案例需要行为判卷；启动失败与 Docker 异常分别记录。
     */
    public BenchmarkBehaviorResult evaluate(String caseId, String projectPath) {
        if (!isBehaviorCase(caseId)) {
            return BenchmarkBehaviorResult.notApplicable();
        }
        if (projectPath == null || projectPath.isBlank()) {
            return BenchmarkBehaviorResult.notRun("生成工作流没有保留项目路径");
        }
        if (!sandbox.isDockerAvailable()) {
            return BenchmarkBehaviorResult.environmentError("Docker 不可用，无法启动外部行为验收");
        }

        String containerId = null;
        try {
            int port = availablePort();
            containerId = sandbox.startSpringBootPreview(java.nio.file.Path.of(projectPath), port);
            long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                if (!sandbox.isContainerRunning(containerId)) {
                    return BenchmarkBehaviorResult.notRun("候选应用启动前退出：" + concise(sandbox.getContainerLogs(containerId)));
                }
                if (STARTED.matcher(sandbox.getContainerLogs(containerId)).find()) {
                    return evaluateAt(caseId, URI.create("http://127.0.0.1:" + port));
                }
                Thread.sleep(1_000);
            }
            return BenchmarkBehaviorResult.notRun("等待候选应用启动超时：" + concise(sandbox.getContainerLogs(containerId)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return BenchmarkBehaviorResult.environmentError("行为验收被中断");
        } catch (Exception e) {
            return BenchmarkBehaviorResult.environmentError(e.getClass().getSimpleName() + ": " + concise(e.getMessage()));
        } finally {
            if (containerId != null) {
                sandbox.stopAndRemoveContainer(containerId);
            }
        }
    }

    static boolean isBehaviorCase(String caseId) {
        return "url-shortener-behavior-v1".equals(caseId) || "poll-system-behavior-v1".equals(caseId);
    }

    /**
     * 可对任意固定 HTTP 产物重复判卷；无需先调用模型生成项目。
     */
    BenchmarkBehaviorResult evaluateAt(String caseId, URI baseUri) {
        if (!isBehaviorCase(caseId)) {
            return BenchmarkBehaviorResult.notApplicable();
        }
        List<BenchmarkBehaviorFinding> findings = new ArrayList<>();
        if ("url-shortener-behavior-v1".equals(caseId)) {
            evaluateShortLinks(baseUri, findings);
        } else {
            evaluatePoll(baseUri, findings);
        }
        return BenchmarkBehaviorResult.fromFindings(findings);
    }

    /**
     * 逐项检查创建、查询、跳转和无效链接，拒绝以仅能构建代替行为正确。
     */
    private void evaluateShortLinks(URI base, List<BenchmarkBehaviorFinding> findings) {
        String original = "https://example.org/docs";
        HttpResponse<String> created = send("POST", base.resolve("/api/links"), "{\"url\":\"" + original + "\"}",
                "link.create", findings);
        String code = created == null ? "" : json(created).path("code").asText("");
        boolean createPassed = created != null && created.statusCode() == 201 && !code.isBlank()
                && original.equals(json(created).path("originalUrl").asText());
        findings.add(finding("link.create", createPassed, created));

        HttpResponse<String> missingInfo = send("GET", base.resolve("/api/links/__missing_baseline_code__"), null,
                "link.missing.query", findings);
        findings.add(finding("link.missing.query", missingInfo != null && missingInfo.statusCode() == 404, missingInfo));
        HttpResponse<String> missingVisit = send("GET", base.resolve("/__missing_baseline_code__"), null,
                "link.missing.visit", findings);
        findings.add(finding("link.missing.visit", missingVisit != null && missingVisit.statusCode() == 404, missingVisit));
        if (!createPassed) {
            return;
        }

        HttpResponse<String> info = send("GET", base.resolve("/api/links/" + code), null,
                "link.query", findings);
        findings.add(finding("link.query", info != null && info.statusCode() == 200
                && code.equals(json(info).path("code").asText())
                && original.equals(json(info).path("originalUrl").asText()), info));
        HttpResponse<String> redirect = send("GET", base.resolve("/" + code), null,
                "link.visit", findings);
        findings.add(finding("link.visit", redirect != null && redirect.statusCode() == 302
                && original.equals(redirect.headers().firstValue("location").orElse("")), redirect));
    }

    /**
     * 用两个身份观察计票，并确认重复投票既拒绝又不改变结果。
     */
    private void evaluatePoll(URI base, List<BenchmarkBehaviorFinding> findings) {
        HttpResponse<String> created = send("POST", base.resolve("/api/polls"),
                "{\"question\":\"选项测试\",\"options\":[\"A\",\"B\"]}", "poll.create", findings);
        String id = created == null ? "" : json(created).path("id").asText("");
        boolean createPassed = created != null && created.statusCode() == 201 && !id.isBlank();
        findings.add(finding("poll.create", createPassed, created));
        if (!createPassed) {
            return;
        }

        URI votes = base.resolve("/api/polls/" + id + "/votes");
        URI results = base.resolve("/api/polls/" + id + "/results");
        HttpResponse<String> initial = send("GET", results, null, "poll.initial", findings);
        findings.add(finding("poll.initial", counts(initial, 0, 0), initial));
        HttpResponse<String> first = send("POST", votes, "{\"voterId\":\"alice\",\"option\":\"A\"}",
                "poll.vote.first", findings);
        findings.add(finding("poll.vote.first", successfulVote(first), first));
        HttpResponse<String> afterFirst = send("GET", results, null, "poll.result.first", findings);
        findings.add(finding("poll.result.first", counts(afterFirst, 1, 0), afterFirst));
        HttpResponse<String> duplicate = send("POST", votes,
                "{\"voterId\":\"alice\",\"option\":\"B\"}", "poll.vote.duplicate", findings);
        findings.add(finding("poll.vote.duplicate", duplicate != null && duplicate.statusCode() == 409, duplicate));
        HttpResponse<String> afterDuplicate = send("GET", results, null, "poll.result.afterDuplicate", findings);
        findings.add(finding("poll.result.afterDuplicate", counts(afterDuplicate, 1, 0), afterDuplicate));
        HttpResponse<String> second = send("POST", votes, "{\"voterId\":\"bob\",\"option\":\"B\"}",
                "poll.vote.second", findings);
        findings.add(finding("poll.vote.second", successfulVote(second), second));
        HttpResponse<String> finalResult = send("GET", results, null, "poll.result.final", findings);
        findings.add(finding("poll.result.final", counts(finalResult, 1, 1), finalResult));
    }

    private boolean successfulVote(HttpResponse<String> response) {
        return response != null && (response.statusCode() == 200 || response.statusCode() == 201);
    }

    private boolean counts(HttpResponse<String> response, int a, int b) {
        return response != null && response.statusCode() == 200
                && json(response).path("counts").path("A").asInt(-1) == a
                && json(response).path("counts").path("B").asInt(-1) == b;
    }

    private HttpResponse<String> send(String method, URI uri, String body, String behavior,
                                      List<BenchmarkBehaviorFinding> findings) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json");
            if (body == null) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/json; charset=UTF-8")
                        .method(method, HttpRequest.BodyPublishers.ofString(body));
            }
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            findings.add(new BenchmarkBehaviorFinding(behavior + ".request", false, "HTTP 请求被中断"));
            return null;
        } catch (IOException | IllegalArgumentException e) {
            findings.add(new BenchmarkBehaviorFinding(behavior + ".request", false,
                    e.getClass().getSimpleName() + ": " + concise(e.getMessage())));
            return null;
        }
    }

    private JsonNode json(HttpResponse<String> response) {
        try {
            return mapper.readTree(response.body());
        } catch (Exception ignored) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
    }

    private BenchmarkBehaviorFinding finding(String behavior, boolean passed, HttpResponse<String> response) {
        return new BenchmarkBehaviorFinding(behavior, passed,
                response == null ? "没有 HTTP 响应" : "HTTP " + response.statusCode()
                        + ", body=" + concise(response.body()));
    }

    private int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String concise(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "...";
    }
}
