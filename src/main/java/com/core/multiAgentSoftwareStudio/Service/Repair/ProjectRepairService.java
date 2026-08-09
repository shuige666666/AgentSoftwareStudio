package com.core.multiAgentSoftwareStudio.Service.Repair;

import com.core.multiAgentSoftwareStudio.Agent.DebuggerAgent;
import com.core.multiAgentSoftwareStudio.Agent.DeveloperAgent;
import com.core.multiAgentSoftwareStudio.Agent.TestWriterAgent;
import com.core.multiAgentSoftwareStudio.Model.Repair.CodeFix;
import com.core.multiAgentSoftwareStudio.Model.Repair.CodeFixResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Generation.TestClassesResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairDecision;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairTarget;
import com.core.multiAgentSoftwareStudio.Service.Context.CodeContextBuilderService;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.FailureTriageService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.RunJournalService;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService.safeValue;

/**
 * 负责根据失败分流调用 TestWriter 或 Debugger，并将整文件修复结果同步到内存和磁盘。
 */
@Service
public class ProjectRepairService {

    private final DebuggerAgent debuggerAgent;
    private final DeveloperAgent developerAgent;
    private final TestWriterAgent testWriterAgent;
    private final WorkspaceService workspaceService;
    private final CodeContextBuilderService codeContextBuilderService;
    private final SourceCodePathService sourceCodePathService;
    private final FailureTriageService failureTriageService;
    private final RunJournalService runJournalService;

    /**
     * 注入修复流程所需的调试 Agent、工作区服务和上下文辅助服务。
     */
    public ProjectRepairService(DebuggerAgent debuggerAgent,
            DeveloperAgent developerAgent,
            TestWriterAgent testWriterAgent,
            WorkspaceService workspaceService,
            CodeContextBuilderService codeContextBuilderService,
            SourceCodePathService sourceCodePathService,
            FailureTriageService failureTriageService,
            RunJournalService runJournalService) {
        this.debuggerAgent = debuggerAgent;
        this.developerAgent = developerAgent;
        this.testWriterAgent = testWriterAgent;
        this.workspaceService = workspaceService;
        this.codeContextBuilderService = codeContextBuilderService;
        this.sourceCodePathService = sourceCodePathService;
        this.failureTriageService = failureTriageService;
        this.runJournalService = runJournalService;
    }

    /**
     * 执行最终修复节点并将修复结果写回工作区
     */
    public void repair(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        if (!data.shouldFix || data.pendingFixLog == null || data.pendingErrorType == null) {
            return;
        }

        RepairDecision decision = failureTriageService.decide(data);
        logger.accept("9. Failure triage: " + decision.reason());
        if (!decision.retryable() || !decision.llmAllowed()) {
            data.shouldFix = false;
            runJournalService.recordRepair(data, decision, false, List.of(), decision.reason());
            return;
        }

        Map<String, String> before = snapshotCodes(data.codes);
        if (decision.target() == RepairTarget.TESTS) {
            regenerateTests(data, logger);
        } else if (decision.target() == RepairTarget.IMPLEMENTATION
                && findReferencedProductionFile(data.codes, data.pendingFixLog) != null) {
            repairImplementation(data, logger);
        } else {
            handleFix(
                    data.codes,
                    data.pendingFixLog,
                    data.pendingErrorType,
                    data.contract,
                    data.projectPath == null ? null : Path.of(data.projectPath),
                    data.validationWarnings,
                    logger);
        }
        data.currentAttempt++;

        List<String> changedFiles = changedFiles(before, snapshotCodes(data.codes));
        boolean changed = !changedFiles.isEmpty();
        if (!changed) {
            // 修复没有产生实际变化时停止，避免下一轮再次发送相同上下文。
            data.noChangeStopCount++;
            data.repairStopRequested = true;
            data.shouldFix = false;
        }
        runJournalService.recordRepair(
                data, decision, changed, changedFiles,
                changed ? "修复已更新 " + changedFiles.size() + " 个文件。" : "修复没有产生实际文件变化。");
    }

    private void handleFix(List<SourceCode> codes,
            String executionResult,
            String errorType,
            ProjectContract contract,
            Path projectPath,
            List<String> validationWarnings,
            Consumer<String> logger) {
        // 修复阶段会把三类信息拼在一起：
        // 1. 当前代码上下文
        // 2. 编译/运行/测试日志
        // 3. 前面轻量校验阶段积累下来的结构警告
        // 这样 debuggerAgent 能同时看到“硬错误”和“软约束”。
        logger.accept("9. Invoking debugger agent for final repair.");

        String currentCodeContext = codeContextBuilderService.buildContractContext(contract)
                + codeContextBuilderService.buildOptimizedCodeContext(codes, executionResult, errorType);
        String enhancedErrorLog = buildEnhancedErrorLog(errorType, executionResult, codes, validationWarnings);

        CodeFixResult fixResult = debuggerAgent.analyzeAndFix(errorType, enhancedErrorLog, currentCodeContext);
        List<CodeFix> fixes = fixResult == null ? null : fixResult.fixes();

        if (fixes == null || fixes.isEmpty()) {
            logger.accept("Debugger agent did not return a concrete fix.");
            return;
        }

        List<CodeFix> normalizedFixes = new ArrayList<>();
        logger.accept("Debugger agent returned " + fixes.size() + " fixes.");
        for (CodeFix fix : fixes) {
            String normalizedFilename = sourceCodePathService.normalizeGeneratedFilename(fix.filename(), fix.newCode());
            CodeFix normalizedFix = new CodeFix(normalizedFilename, fix.explanation(), fix.newCode());
            logger.accept("   - " + safeValue(fix.explanation(), "No explanation provided"));
            sourceCodePathService.applyCodeFix(codes, normalizedFilename, fix.newCode());
            normalizedFixes.add(normalizedFix);
        }
        if (projectPath != null) {
            workspaceService.applyFixesToDisk(projectPath, normalizedFixes, logger);
        }
    }

    /**
     * 测试编译或测试发现失败时复用 TestWriter 的一次调用，并消耗同一份修复预算。
     */
    private void regenerateTests(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        logger.accept("9. Regenerating tests for a test-owned failure.");
        TestClassesResult result = testWriterAgent.rewriteTests(
                data.prd,
                data.structure,
                data.contract,
                codeContextBuilderService.buildCodeContextForTester(data.codes),
                data.pendingFixLog);
        if (result == null || result.testFiles() == null || result.testFiles().isEmpty()) {
            logger.accept("TestWriter did not return concrete test files.");
            return;
        }

        List<CodeFix> diskFixes = new ArrayList<>();
        for (SourceCode testFile : result.testFiles()) {
            if (testFile == null) {
                continue;
            }
            String filename = sourceCodePathService.normalizeGeneratedFilename(testFile.filename(), testFile.code());
            sourceCodePathService.upsertSourceCode(data.codes, filename, testFile.code());
            diskFixes.add(new CodeFix(filename, "TestWriter 按失败分类重新生成测试", testFile.code()));
        }
        if (data.projectPath != null && !diskFixes.isEmpty()) {
            workspaceService.applyFixesToDisk(Path.of(data.projectPath), diskFixes, logger);
        }
    }

    /**
     * 编译日志能稳定定位生产文件时交给 Developer 单文件修复，避免 Debugger 无边界修改多处代码。
     */
    private void repairImplementation(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        SourceCode target = findReferencedProductionFile(data.codes, data.pendingFixLog);
        if (target == null) {
            return;
        }
        String filename = sourceCodePathService.normalizeGeneratedFilename(target.filename(), target.code());
        logger.accept("9. Routing implementation-owned failure to Developer: " + filename);
        SourceCode repaired = developerAgent.writeCode(
                data.prd,
                data.structure,
                data.contract,
                codeContextBuilderService.buildOptimizedCodeContext(
                        data.codes, data.pendingFixLog, data.pendingErrorType),
                filename,
                "Repair the referenced production file for this failure: " + data.pendingFixLog,
                "Preserve existing public contracts and implement the complete corrected file.",
                "Failure repair; do not modify unrelated files.");
        if (repaired == null || repaired.code() == null) {
            logger.accept("Developer did not return a concrete implementation fix.");
            return;
        }
        sourceCodePathService.upsertSourceCode(data.codes, filename, repaired.code());
        if (data.projectPath != null) {
            workspaceService.applyFixesToDisk(
                    Path.of(data.projectPath),
                    List.of(new CodeFix(filename, "Developer 按实现失败分类修复", repaired.code())),
                    logger);
        }
    }

    private SourceCode findReferencedProductionFile(List<SourceCode> codes, String failureLog) {
        if (codes == null || failureLog == null || failureLog.isBlank()) {
            return null;
        }
        for (SourceCode code : codes) {
            if (code == null) {
                continue;
            }
            String filename = sourceCodePathService.normalizeGeneratedFilename(code.filename(), code.code());
            if (!filename.startsWith("src/main/") || sourceCodePathService.isFrontendFile(filename)) {
                continue;
            }
            String pureName = Path.of(filename).getFileName().toString();
            if (failureLog.contains(filename) || failureLog.contains(filename.replace('/', '\\'))
                    || failureLog.contains(pureName)) {
                return code;
            }
        }
        return null;
    }

    private Map<String, String> snapshotCodes(List<SourceCode> codes) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        if (codes == null) {
            return snapshot;
        }
        for (SourceCode code : codes) {
            if (code == null) {
                continue;
            }
            String filename = sourceCodePathService.normalizeGeneratedFilename(code.filename(), code.code());
            snapshot.put(filename, code.code() == null ? "" : code.code());
        }
        return snapshot;
    }

    private List<String> changedFiles(Map<String, String> before, Map<String, String> after) {
        List<String> changed = new ArrayList<>();
        java.util.LinkedHashSet<String> filenames = new java.util.LinkedHashSet<>(before.keySet());
        filenames.addAll(after.keySet());
        for (String filename : filenames) {
            if (!java.util.Objects.equals(before.get(filename), after.get(filename))) {
                changed.add(filename);
            }
        }
        return List.copyOf(changed);
    }

    /**
     * 组合原始错误日志、分类提示和轻量校验告警
     */
    private String buildEnhancedErrorLog(String errorType,
            String executionResult,
            List<SourceCode> codes,
            List<String> validationWarnings) {
        // 编译错误除了原始日志，还会额外拼接一层“启发式提示”。
        // 同时把中间轻量校验发现的问题一起给 debugger，减少来回试错。
        StringBuilder builder = new StringBuilder(executionResult == null ? "" : executionResult);

        if ("COMPILATION ERROR".equals(errorType)
                || "MAIN_COMPILE".equals(errorType)
                || "TEST_COMPILE".equals(errorType)) {
            String classification = classifyCompilationErrors(executionResult == null ? "" : executionResult, codes);
            if (!classification.isBlank()) {
                builder.append("\n\n=== COMPILATION ERROR CLASSIFIER ===\n").append(classification);
            }
        }

        if (validationWarnings != null && !validationWarnings.isEmpty()) {
            builder.append("\n\n=== LIGHTWEIGHT VALIDATION WARNINGS ===\n");
            validationWarnings.forEach(warning -> builder.append("- ").append(warning).append('\n'));
        }
        return builder.toString();
    }

    /**
     * 对常见编译错误给出启发式分类提示
     */
    private String classifyCompilationErrors(String executionResult, List<SourceCode> codes) {
        // 这里不是严格的编译器分析器，只是把项目里高频出现的错误模式做成提示。
        List<String> hints = new ArrayList<>();

        boolean hasJUnitMissing = executionResult.contains("package org.junit.jupiter")
                || executionResult.contains("package org.mockito")
                || executionResult.contains("org.junit.jupiter.api does not exist")
                || executionResult.contains("org.mockito does not exist");
        if (hasJUnitMissing) {
            List<String> misplacedTests = findMisplacedTestFiles(codes);
            if (!misplacedTests.isEmpty()) {
                hints.add("Tests appear to be under src/main/java instead of src/test/java: "
                        + String.join(", ", misplacedTests));
            } else {
                hints.add("Test dependency issue detected. Check whether test classes are under src/test/java.");
            }
        }

        boolean hasMethodBodyIssue = executionResult.contains("missing method body")
                || executionResult.contains("or declare abstract")
                || executionResult.contains("abstract methods")
                || executionResult.contains("is not abstract and does not override abstract method");
        if (hasMethodBodyIssue) {
            hints.add("A non-abstract method or constructor is declared without an implementation body.");
        }

        boolean missingList = executionResult.contains("symbol:   class List")
                || executionResult.contains("symbol: class List")
                || executionResult.contains("cannot find symbol")
                        && (executionResult.contains(" List ") || executionResult.contains("List<"));
        if (missingList) {
            hints.add("Missing import likely: import java.util.List;");
        }

        if (executionResult.contains("symbol:   class ResponseEntity")
                || executionResult.contains("symbol: class ResponseEntity")) {
            hints.add("Missing import likely: import org.springframework.http.ResponseEntity;");
        }
        if (executionResult.contains("symbol:   class RequestParam")
                || executionResult.contains("symbol: class RequestParam")) {
            hints.add("Missing import likely: import org.springframework.web.bind.annotation.RequestParam;");
        }
        if (executionResult.contains("symbol:   class PathVariable")
                || executionResult.contains("symbol: class PathVariable")) {
            hints.add("Missing import likely: import org.springframework.web.bind.annotation.PathVariable;");
        }

        return String.join("\n", hints);
    }

    /**
     * 找出被错误放入主源码目录的测试文件
     */
    private List<String> findMisplacedTestFiles(List<SourceCode> codes) {
        // 一些测试文件被错误放进 src/main/java 时，会引发一串看起来很乱的依赖错误。
        // 这里提前把这种模式抽出来，方便修复 prompt 更精准。
        List<String> misplacedFiles = new ArrayList<>();
        for (SourceCode code : codes) {
            String normalizedFilename = sourceCodePathService.normalizeGeneratedFilename(code.filename(), code.code()).replace("\\", "/");
            boolean looksLikeTest = normalizedFilename.endsWith("Test.java")
                    || (code.code() != null
                            && (code.code().contains("org.junit.jupiter") || code.code().contains("@Test")));
            if (looksLikeTest && normalizedFilename.startsWith("src/main/java/")) {
                misplacedFiles.add(normalizedFilename);
            }
        }
        return misplacedFiles;
    }
}
