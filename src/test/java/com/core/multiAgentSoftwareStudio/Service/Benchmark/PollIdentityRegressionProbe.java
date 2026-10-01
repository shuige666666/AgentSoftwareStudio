package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult;
import com.core.multiAgentSoftwareStudio.SoftwareStudioApplication;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.*;
import java.net.URI;
import java.nio.file.*;

/** 对保存的候选补验重复拒绝后的身份状态；不修改冻结判卷，不调用模型。 */
public final class PollIdentityRegressionProbe extends FixedHttpChecks {
    PollIdentityRegressionProbe(URI base) { super(base); }

    /** 首次成功后连续拒绝两次，检查被拒绝请求不会撤销已有投票身份。 */
    @Override BenchmarkBehaviorResult evaluate() {
        var created = jsonRequest("POST", "/api/polls", "{\"question\":\"Identity invariant\",\"options\":[\"A\",\"B\"]}");
        String id = json(created).path("id").asText("");
        if (created.status() != 201 || id.isBlank()) {
            check("identity.create", created, false, "201; 可用 id");
            for (String name : java.util.List.of("first", "duplicate", "repeat", "preserved")) blocked("identity." + name, "identity.create");
            return BenchmarkBehaviorResult.fromFindings(findings);
        }
        String path = "/api/polls/" + id;
        var first = jsonRequest("POST", path + "/votes", "{\"voterId\":\"alice\",\"option\":\"A\"}");
        check("identity.first", first, first.status() == 200, "200; 首次投票");
        var duplicate = jsonRequest("POST", path + "/votes", "{\"voterId\":\"alice\",\"option\":\"B\"}");
        check("identity.duplicate", duplicate, duplicate.status() == 409, "409; 重复投票");
        var repeated = jsonRequest("POST", path + "/votes", "{\"voterId\":\"alice\",\"option\":\"A\"}");
        check("identity.repeat", repeated, repeated.status() == 409, "409; 拒绝后再次重复也必须拒绝");
        var counts = jsonRequest("GET", path + "/results", null);
        check("identity.preserved", counts, counts.status() == 200 && json(counts).path("counts").path("A").asInt(-1) == 1
                && json(counts).path("counts").path("B").asInt(-1) == 0, "200; 始终保持 A=1,B=0");
        return BenchmarkBehaviorResult.fromFindings(findings);
    }

    /** 仅手工启动本机 Docker 预览，输出额外证据，不改原候选或实验报告。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: PollIdentityRegressionProbe <saved-project> <new-report.json>");
        Path output = Path.of(args[1]).toAbsolutePath();
        if (Files.exists(output)) throw new IllegalStateException("Preserve existing evidence: " + output);
        var app = new SpringApplication(SoftwareStudioApplication.class); app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run("--spring.main.banner-mode=off")) {
            var sandbox = context.getBean(DockerSandboxService.class);
            int port; try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
            String container = sandbox.startSpringBootPreview(Path.of(args[0]), port);
            try {
                long deadline = System.nanoTime() + java.time.Duration.ofMinutes(2).toNanos();
                boolean ready = false;
                while (System.nanoTime() < deadline && sandbox.isContainerRunning(container)) {
                    if (sandbox.getContainerLogs(container).matches("(?s).*\\bStarted\\s+\\S+\\s+in\\s+\\d.*")) { ready = true; break; }
                    Thread.sleep(1000);
                }
                if (!ready) throw new IllegalStateException("Saved candidate did not start");
                var result = new PollIdentityRegressionProbe(URI.create("http://127.0.0.1:" + port)).evaluate();
                Files.createDirectories(output.getParent());
                context.getBean(ObjectMapper.class).writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
                System.out.println("SUPPLEMENTAL_IDENTITY_RESULT=" + result.status());
            } finally { sandbox.stopAndRemoveContainer(container); }
        }
    }
}
