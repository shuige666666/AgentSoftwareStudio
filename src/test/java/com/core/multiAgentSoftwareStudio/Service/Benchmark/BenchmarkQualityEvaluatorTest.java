package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.SourceCode;
import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.WorkflowExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkQualityEvaluatorTest {

    private final BenchmarkQualityEvaluator evaluator = new BenchmarkQualityEvaluator();
    private final BenchmarkCase benchmarkCase = new BenchmarkCase(
            "test-case", "test", "prompt", Set.of(
            BenchmarkGate.NO_PLACEHOLDER_SOURCE,
            BenchmarkGate.HAS_TEST_SOURCE,
            BenchmarkGate.TESTS_EXECUTED_AND_GREEN,
            BenchmarkGate.HAS_SPRING_CONTEXT_TEST));

    @Test
    void rejectsPlatformSuccessWhenNoTestsActuallyRan() {
        WorkflowExecutionResult execution = execution(true,
                List.of(
                        new SourceCode("src/main/java/com/example/App.java", "java",
                                "@SpringBootApplication class App {}"),
                        new SourceCode("src/test/java/com/example/AppTest.java", "java", "@SpringBootTest class AppTest {}")),
                "[INFO] No tests to run.\n[INFO] BUILD SUCCESS");

        BenchmarkQualityResult result = evaluator.evaluate(benchmarkCase, execution);

        assertFalse(result.passed());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.gate() == BenchmarkGate.TESTS_EXECUTED_AND_GREEN
                && !finding.passed()));
    }

    @Test
    void acceptsRealGreenTestEvidenceAndRequiredSources() {
        WorkflowExecutionResult execution = execution(true,
                List.of(
                        new SourceCode("src/main/java/com/example/App.java", "java",
                                "@SpringBootApplication class App {}"),
                        new SourceCode("src/test/java/com/example/AppTest.java", "java",
                                "@SpringBootTest class AppTest { @Test void starts() {} }")),
                "Tests run: 2, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS");

        assertTrue(evaluator.evaluate(benchmarkCase, execution).passed());
    }

    @Test
    void rejectsPackageEllipsisEvenWhenPlatformReportedSuccess() {
        WorkflowExecutionResult execution = execution(true,
                List.of(new SourceCode("src/main/java/com/example/Broken.java", "java", "package ...;")),
                "Tests run: 1, Failures: 0, Errors: 0");

        BenchmarkQualityResult result = evaluator.evaluate(benchmarkCase, execution);

        assertFalse(result.passed());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.gate() == BenchmarkGate.NO_PLACEHOLDER_SOURCE
                && !finding.passed()));
    }

    private WorkflowExecutionResult execution(boolean platformSuccess, List<SourceCode> codes, String testResult) {
        return new WorkflowExecutionResult(codes, platformSuccess, "generated", "BUILD SUCCESS", testResult, List.of(), null, 1,
                new LlmUsageMetricsService.Snapshot(1, 1, 0, 1, 0, 1, 1, 2));
    }
}
