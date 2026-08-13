package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkCase;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkGate;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkQualityResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectQualityPolicyResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.QualityGateSeverity;
import com.core.multiAgentSoftwareStudio.Model.Generation.QualityPolicyFinding;
import com.core.multiAgentSoftwareStudio.Model.Metric.LlmUsageSnapshot;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowExecutionResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.SandboxExecutionResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStage;
import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowRunSummary;
import com.core.multiAgentSoftwareStudio.Service.Workflow.VerificationResultService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkQualityEvaluatorTest {

    private final BenchmarkQualityEvaluator evaluator = new BenchmarkQualityEvaluator();
    private final VerificationResultService verificationResultService = new VerificationResultService();
    private final BenchmarkCase benchmarkCase = new BenchmarkCase(
            "test-case", "test", "prompt", Set.of(
            BenchmarkGate.NO_PLACEHOLDER_SOURCE,
            BenchmarkGate.HAS_TEST_SOURCE,
            BenchmarkGate.TESTS_EXECUTED_AND_GREEN,
            BenchmarkGate.HAS_SPRING_CONTEXT_TEST,
            BenchmarkGate.CONTRACT_GATES_PASSED));

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

    /**
     * 编译和测试全绿时，最终契约阻断项仍必须让独立质量评价失败。
     */
    @Test
    void rejectsFinalContractBlockerEvenWhenTestsAreGreen() {
        WorkflowExecutionResult execution = execution(true,
                List.of(
                        new SourceCode("src/main/java/com/example/App.java", "java",
                                "@SpringBootApplication class App {}"),
                        new SourceCode("src/test/java/com/example/AppTest.java", "java",
                                "@SpringBootTest class AppTest { @Test void starts() {} }")),
                "Tests run: 2, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS",
                new ProjectQualityPolicyResult(false, List.of(
                        new QualityPolicyFinding("FRONTEND_PAYLOAD_FIELDS", QualityGateSeverity.BLOCK,
                                FailureKind.CONTRACT, "payload mismatch")), List.of()));

        BenchmarkQualityResult result = evaluator.evaluate(benchmarkCase, execution);

        assertFalse(result.passed());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.gate() == BenchmarkGate.CONTRACT_GATES_PASSED
                && !finding.passed()));
    }

    private WorkflowExecutionResult execution(boolean platformSuccess, List<SourceCode> codes, String testResult) {
        return execution(platformSuccess, codes, testResult, ProjectQualityPolicyResult.empty());
    }

    private WorkflowExecutionResult execution(boolean platformSuccess,
            List<SourceCode> codes,
            String testResult,
            ProjectQualityPolicyResult qualityPolicyResult) {
        var build = verificationResultService.toStep(
                VerificationStage.BUILD,
                new SandboxExecutionResult("mvn package", 0, "BUILD SUCCESS", 1, false, null));
        var test = verificationResultService.toStep(
                VerificationStage.TEST,
                new SandboxExecutionResult("mvn test", 0, testResult, 1, false, null));
        return new WorkflowExecutionResult(codes, platformSuccess, "generated", "BUILD SUCCESS", testResult, List.of(), null, 1,
                new LlmUsageSnapshot(1, 1, 0, 1, 0, 1, 1, 2, false),
                qualityPolicyResult,
                VerificationResult.afterBuild(build).withTest(test),
                WorkflowRunSummary.empty());
    }
}
