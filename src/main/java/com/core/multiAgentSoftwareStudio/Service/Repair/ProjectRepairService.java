package com.core.multiAgentSoftwareStudio.Service.Repair;

import com.core.multiAgentSoftwareStudio.Agent.DebuggerAgent;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.CodeFix;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.CodeFixResult;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.ProjectContract;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.SourceCode;
import com.core.multiAgentSoftwareStudio.Service.Context.CodeContextBuilderService;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 负责调用调试 Agent 分析编译、运行、测试错误，并将整文件修复结果同步到内存和磁盘。
 */
@Service
public class ProjectRepairService {

    private final DebuggerAgent debuggerAgent;
    private final WorkspaceService workspaceService;
    private final CodeContextBuilderService codeContextBuilderService;
    private final SourceCodePathService sourceCodePathService;

    /**
     * 注入修复流程所需的调试 Agent、工作区服务和上下文辅助服务。
     */
    public ProjectRepairService(DebuggerAgent debuggerAgent,
            WorkspaceService workspaceService,
            CodeContextBuilderService codeContextBuilderService,
            SourceCodePathService sourceCodePathService) {
        this.debuggerAgent = debuggerAgent;
        this.workspaceService = workspaceService;
        this.codeContextBuilderService = codeContextBuilderService;
        this.sourceCodePathService = sourceCodePathService;
    }

    /**
     * 执行最终修复节点并将修复结果写回工作区
     */
    public void repair(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        if (!data.shouldFix || data.pendingFixLog == null || data.pendingErrorType == null) {
            return;
        }

        // fix 节点本身不做复杂路由，只负责把需要的上下文拼好，
        // 然后交给 debuggerAgent 给出整文件修复结果。
        handleFix(
                data.codes,
                data.pendingFixLog,
                data.pendingErrorType,
                data.contract,
                Path.of(data.projectPath),
                data.validationWarnings,
                logger);
        data.currentAttempt++;
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
            updateCodesInMemory(codes, normalizedFix);
            normalizedFixes.add(normalizedFix);
        }
        workspaceService.applyFixesToDisk(projectPath, normalizedFixes, logger);
    }

    /**
     * 用修复后的代码更新内存中的文件列表
     */
    private void updateCodesInMemory(List<SourceCode> codes, CodeFix fix) {
        // debugger 返回修复结果后，先更新内存，再落盘。
        // 后续重跑时用到的是修复后的最新版本，而不是旧代码。
        boolean found = false;
        String normalizedFixFilename = sourceCodePathService.normalizeGeneratedFilename(fix.filename(), fix.newCode());
        String fixPureName = Path.of(normalizedFixFilename).getFileName().toString();

        for (int i = 0; i < codes.size(); i++) {
            String existingFilename = sourceCodePathService.normalizeGeneratedFilename(codes.get(i).filename(), codes.get(i).code());
            if (existingFilename.equals(normalizedFixFilename)) {
                codes.set(i, new SourceCode(existingFilename, codes.get(i).language(), fix.newCode()));
                found = true;
                break;
            }
            String existingPureName = Path.of(existingFilename).getFileName().toString();
            if (existingPureName.equals(fixPureName)) {
                codes.set(i, new SourceCode(existingFilename, codes.get(i).language(), fix.newCode()));
                found = true;
                break;
            }
        }

        if (!found) {
            codes.add(new SourceCode(normalizedFixFilename, sourceCodePathService.detectLanguageFromFilename(normalizedFixFilename),
                    fix.newCode()));
        }
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

        if ("COMPILATION ERROR".equals(errorType)) {
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

    /**
     * 在值为空时返回兜底文本
     */
    private String safeValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
