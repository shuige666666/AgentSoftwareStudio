package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.SourceCode;
import com.core.multiAgentSoftwareStudio.Service.Workflow.WorkflowExecutionResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 与工作流 success 字段分离的质量判定器。
 *
 * 它只使用基准任务声明的门禁和真实产物/真实测试输出，不接受“没有测试摘要也算成功”的规则。
 */
@Service
public class BenchmarkQualityEvaluator {

    private static final Pattern INVALID_PACKAGE_PLACEHOLDER = Pattern.compile("(?m)^\\s*package\\s+\\.\\.\\.\\s*;");

    public BenchmarkQualityResult evaluate(BenchmarkCase benchmarkCase, WorkflowExecutionResult execution) {
        List<SourceCode> codes = execution == null || execution.codes() == null ? List.of() : execution.codes();
        List<BenchmarkFinding> findings = new ArrayList<>();

        for (BenchmarkGate gate : benchmarkCase.requiredGates()) {
            findings.add(evaluateGate(gate, codes, execution));
        }
        return new BenchmarkQualityResult(findings.stream().allMatch(BenchmarkFinding::passed), List.copyOf(findings));
    }

    private BenchmarkFinding evaluateGate(BenchmarkGate gate,
            List<SourceCode> codes,
            WorkflowExecutionResult execution) {
        return switch (gate) {
            case NO_PLACEHOLDER_SOURCE -> noPlaceholderSource(codes);
            case HAS_TEST_SOURCE -> hasTestSource(codes);
            case TESTS_EXECUTED_AND_GREEN -> testsExecutedAndGreen(execution);
            case HAS_SPRING_CONTEXT_TEST -> hasSpringContextTest(codes);
        };
    }

    private BenchmarkFinding noPlaceholderSource(List<SourceCode> codes) {
        for (SourceCode code : codes) {
            String content = code == null || code.code() == null ? "" : code.code();
            String lower = content.toLowerCase(Locale.ROOT);
            if (INVALID_PACKAGE_PLACEHOLDER.matcher(content).find()
                    || lower.contains("todo: implement")
                    || lower.contains("implement logic")
                    || lower.contains("placeholder implementation")) {
                return new BenchmarkFinding(BenchmarkGate.NO_PLACEHOLDER_SOURCE, false,
                        "Placeholder source found in " + (code == null ? "<null>" : code.filename()));
            }
        }
        return new BenchmarkFinding(BenchmarkGate.NO_PLACEHOLDER_SOURCE, true,
                "No known placeholder source was found.");
    }

    private BenchmarkFinding hasTestSource(List<SourceCode> codes) {
        boolean found = codes.stream().filter(java.util.Objects::nonNull).anyMatch(code -> {
            String filename = code.filename() == null ? "" : code.filename().replace('\\', '/');
            String content = code.code() == null ? "" : code.code();
            return filename.startsWith("src/test/java/") || content.contains("@Test");
        });
        return new BenchmarkFinding(BenchmarkGate.HAS_TEST_SOURCE, found,
                found ? "Generated test source exists." : "No generated JUnit test source was found.");
    }

    private BenchmarkFinding testsExecutedAndGreen(WorkflowExecutionResult execution) {
        String output = execution == null || execution.testResult() == null ? "" : execution.testResult();
        boolean ranTests = output.contains("Tests run:") && !output.contains("No tests to run");
        boolean green = output.contains("Failures: 0") && output.contains("Errors: 0")
                && !output.contains("BUILD FAILURE") && !output.contains("There are test failures");
        boolean passed = ranTests && green;
        String evidence = passed
                ? "Generated tests executed and reported zero failures and errors."
                : "Tests must execute and report Failures: 0 plus Errors: 0; output=" + summarize(output);
        return new BenchmarkFinding(BenchmarkGate.TESTS_EXECUTED_AND_GREEN, passed, evidence);
    }

    private BenchmarkFinding hasSpringContextTest(List<SourceCode> codes) {
        boolean found = codes.stream().filter(java.util.Objects::nonNull)
                .anyMatch(code -> (code.code() == null ? "" : code.code()).contains("@SpringBootTest"));
        return new BenchmarkFinding(BenchmarkGate.HAS_SPRING_CONTEXT_TEST, found,
                found ? "Generated Spring context test exists." : "No @SpringBootTest was generated.");
    }

    private String summarize(String output) {
        String normalized = output.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
    }
}
