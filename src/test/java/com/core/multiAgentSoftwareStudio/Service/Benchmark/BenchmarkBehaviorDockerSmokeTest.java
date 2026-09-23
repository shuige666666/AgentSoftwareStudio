package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 显式指定历史产物时，验证 Docker 启动和外部 HTTP 判卷的真实连接。
 */
class BenchmarkBehaviorDockerSmokeTest {
    @Test
    @EnabledIfSystemProperty(named = "baseline.project.path", matches = ".+")
    void judgesHistoricalProjectWithoutUsingItsOwnTests() throws Exception {
        String path = System.getProperty("baseline.project.path");
        String caseId = System.getProperty("baseline.case.id", "url-shortener-behavior-v1");
        var evaluator = new BenchmarkBehaviorEvaluator(new DockerSandboxService(), new ObjectMapper());

        var result = evaluator.evaluate(caseId, path);

        System.out.println("[Baseline HTTP] " + caseId + " " + result);
        Path reportRoot = Path.of("benchmark-results");
        Files.createDirectories(reportRoot);
        Path report = reportRoot.resolve("behavior-recheck-" + caseId + "-"
                + Instant.now().toString().replace(':', '-') + ".json");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(report.toFile(), Map.of(
                "evaluatedAt", Instant.now().toString(),
                "caseId", caseId,
                "projectPath", path,
                "behavior", result));
        System.out.println("[Baseline HTTP] report=" + report.toAbsolutePath());
        // 通过与失败都是有效的 HTTP 判卷；环境错误不能伪装成业务失败。
        assertTrue("PASS".equals(result.status()) || "FAIL".equals(result.status()),
                () -> "判卷未到达 HTTP 行为检查：" + result);
    }
}
