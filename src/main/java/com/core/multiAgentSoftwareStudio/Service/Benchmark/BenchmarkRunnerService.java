package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.WorkflowExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 顺序执行真实 LLM 基准任务。该服务只会被显式 HTTP 请求调用，绝不会由单元测试触发。
 */
@Service
public class BenchmarkRunnerService {

    private static final Path REPORT_ROOT = Path.of("benchmark-results");

    private final BenchmarkCaseRegistry caseRegistry;
    private final BenchmarkQualityEvaluator qualityEvaluator;
    private final SoftwareStudioWorkflowService workflowService;
    private final ObjectMapper objectMapper;

    public BenchmarkRunnerService(BenchmarkCaseRegistry caseRegistry,
            BenchmarkQualityEvaluator qualityEvaluator,
            SoftwareStudioWorkflowService workflowService,
            ObjectMapper objectMapper) {
        this.caseRegistry = caseRegistry;
        this.qualityEvaluator = qualityEvaluator;
        this.workflowService = workflowService;
        this.objectMapper = objectMapper;
    }

    public List<BenchmarkCase> listCases() {
        return caseRegistry.allCases();
    }

    public BenchmarkRunReport run(BenchmarkRunRequest request) {
        List<BenchmarkCase> cases = caseRegistry.select(request == null ? List.of() : request.caseIds());
        int maxRetries = request == null || request.maxRetries() == null ? 2 : Math.max(1, request.maxRetries());
        Instant started = Instant.now();
        List<BenchmarkCaseResult> results = new ArrayList<>();

        for (BenchmarkCase benchmarkCase : cases) {
            results.add(runCase(benchmarkCase, maxRetries));
        }

        Instant finished = Instant.now();
        int platformSuccessCount = (int) results.stream().filter(BenchmarkCaseResult::platformSuccess).count();
        int independentPassCount = (int) results.stream().filter(BenchmarkCaseResult::independentQualityPassed).count();
        int falseSuccessCount = (int) results.stream()
                .filter(result -> result.platformSuccess() && !result.independentQualityPassed())
                .count();
        Double falseSuccessRate = platformSuccessCount == 0 ? null : (double) falseSuccessCount / platformSuccessCount;

        BenchmarkRunReport withoutPath = new BenchmarkRunReport(
                started.toString(), finished.toString(), List.copyOf(results), platformSuccessCount,
                independentPassCount, falseSuccessCount, falseSuccessRate, null);
        String reportPath = reportPathFor(started).toAbsolutePath().toString();
        persist(new BenchmarkRunReport(
                withoutPath.startedAt(), withoutPath.finishedAt(), withoutPath.cases(),
                withoutPath.platformSuccessCount(), withoutPath.independentQualityPassCount(),
                withoutPath.falseSuccessCount(), withoutPath.falseSuccessRate(), reportPath),
                Path.of(reportPath));
        return new BenchmarkRunReport(
                withoutPath.startedAt(), withoutPath.finishedAt(), withoutPath.cases(),
                withoutPath.platformSuccessCount(), withoutPath.independentQualityPassCount(),
                withoutPath.falseSuccessCount(), withoutPath.falseSuccessRate(), reportPath);
    }

    private BenchmarkCaseResult runCase(BenchmarkCase benchmarkCase, int maxRetries) {
        long started = System.nanoTime();
        try {
            WorkflowExecutionResult execution = workflowService.generateProjectWithResult(
                    benchmarkCase.prompt(),
                    message -> System.out.println("[Benchmark " + benchmarkCase.id() + "] " + message),
                    maxRetries);
            BenchmarkQualityResult quality = qualityEvaluator.evaluate(benchmarkCase, execution);
            return new BenchmarkCaseResult(
                    benchmarkCase.id(), execution.platformSuccess(), quality.passed(), "NOT_REVIEWED",
                    execution.projectPath(), elapsedMillis(started), quality, execution.usage(), null);
        } catch (Exception e) {
            BenchmarkQualityResult quality = new BenchmarkQualityResult(false, List.of());
            return new BenchmarkCaseResult(
                    benchmarkCase.id(), false, false, "NOT_REVIEWED", null, elapsedMillis(started), quality,
                    new LlmUsageMetricsService.Snapshot(0, 0, 0, 0, 0, 0, 0, 0),
                    e.getClass().getSimpleName() + ": " + safeMessage(e));
        }
    }

    private Path reportPathFor(Instant started) {
        String name = "benchmark-" + DateTimeFormatter.ISO_INSTANT.format(started).replace(':', '-') + ".json";
        return REPORT_ROOT.resolve(name);
    }

    private void persist(BenchmarkRunReport report, Path reportPath) {
        try {
            Files.createDirectories(REPORT_ROOT);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist benchmark report", e);
        }
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private String safeMessage(Exception error) {
        return error.getMessage() == null ? "No message" : error.getMessage();
    }
}
