package com.core.multiAgentSoftwareStudio.Service.Repair;

import com.core.multiAgentSoftwareStudio.Agent.DebuggerAgent;
import com.core.multiAgentSoftwareStudio.Agent.ContractRepairAgent;
import com.core.multiAgentSoftwareStudio.Agent.DeveloperAgent;
import com.core.multiAgentSoftwareStudio.Agent.ImplementationRepairAgent;
import com.core.multiAgentSoftwareStudio.Agent.TestWriterAgent;
import com.core.multiAgentSoftwareStudio.Model.Repair.CodeFix;
import com.core.multiAgentSoftwareStudio.Model.Repair.CodeFixResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Generation.TestClassesResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairDecision;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairTarget;
import com.core.multiAgentSoftwareStudio.Model.Workflow.TestScopeViolation;
import com.core.multiAgentSoftwareStudio.Service.Context.CodeContextBuilderService;
import com.core.multiAgentSoftwareStudio.Service.Generation.SliceTestScopeService;
import com.core.multiAgentSoftwareStudio.Service.Generation.SliceProductionScopeService;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.FailureTriageService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.RunJournalService;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

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
    private final ImplementationRepairAgent implementationRepairAgent;
    private final ContractRepairAgent contractRepairAgent;
    private final DeveloperAgent developerAgent;
    private final TestWriterAgent testWriterAgent;
    private final WorkspaceService workspaceService;
    private final CodeContextBuilderService codeContextBuilderService;
    private final SourceCodePathService sourceCodePathService;
    private final SliceTestScopeService sliceTestScopeService;
    private final SliceProductionScopeService sliceProductionScopeService;
    private final FailureTriageService failureTriageService;
    private final RunJournalService runJournalService;

    /**
     * 注入修复流程所需的调试 Agent、工作区服务和上下文辅助服务。
     */
    @Autowired
    public ProjectRepairService(DebuggerAgent debuggerAgent,
            ImplementationRepairAgent implementationRepairAgent,
            ContractRepairAgent contractRepairAgent,
            DeveloperAgent developerAgent,
            TestWriterAgent testWriterAgent,
            WorkspaceService workspaceService,
            CodeContextBuilderService codeContextBuilderService,
            SourceCodePathService sourceCodePathService,
            SliceTestScopeService sliceTestScopeService,
            SliceProductionScopeService sliceProductionScopeService,
            FailureTriageService failureTriageService,
            RunJournalService runJournalService) {
        this.debuggerAgent = debuggerAgent;
        this.implementationRepairAgent = implementationRepairAgent;
        this.contractRepairAgent = contractRepairAgent;
        this.developerAgent = developerAgent;
        this.testWriterAgent = testWriterAgent;
        this.workspaceService = workspaceService;
        this.codeContextBuilderService = codeContextBuilderService;
        this.sourceCodePathService = sourceCodePathService;
        this.sliceTestScopeService = sliceTestScopeService;
        this.sliceProductionScopeService = sliceProductionScopeService;
        this.failureTriageService = failureTriageService;
        this.runJournalService = runJournalService;
    }

    /**
     * 保留旧构造器供现有轻量测试使用；缺少专项 Agent 时安全退回通用 Debugger。
     */
    public ProjectRepairService(DebuggerAgent debuggerAgent,
            DeveloperAgent developerAgent,
            TestWriterAgent testWriterAgent,
            WorkspaceService workspaceService,
            CodeContextBuilderService codeContextBuilderService,
            SourceCodePathService sourceCodePathService,
            SliceTestScopeService sliceTestScopeService,
            SliceProductionScopeService sliceProductionScopeService,
            FailureTriageService failureTriageService,
            RunJournalService runJournalService) {
        this(debuggerAgent, null, null, developerAgent, testWriterAgent, workspaceService,
                codeContextBuilderService, sourceCodePathService, sliceTestScopeService,
                sliceProductionScopeService, failureTriageService, runJournalService);
    }

    /**
     * 执行最终修复节点并将修复结果写回工作区
     */
    public void repair(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        if (!data.shouldFix || data.pendingFixLog == null || data.pendingErrorType == null) {
            return;
        }

        data.resumeSliceTestGeneration = false;
        RepairDecision decision = failureTriageService.decide(data);
        logger.accept("9. Failure triage: " + decision.reason());
        if (!decision.retryable() || !decision.llmAllowed()) {
            data.shouldFix = false;
            runJournalService.recordRepair(data, decision, false, List.of(), decision.reason());
            return;
        }

        // 初始生成不计入预算；只有真正即将调用修复 Agent 时才消费一次全局额度。
        data.consumeRepairBudget();

        Map<String, String> before = snapshotCodes(data.codes);
        captureRepairBaseline(data, before);
        if (decision.target() == RepairTarget.TESTS) {
            regenerateTests(data, logger);
        } else if (decision.target() == RepairTarget.IMPLEMENTATION
                && (isFutureTypeBoundaryFailure(data.pendingFixLog)
                        || requiresCrossFileImplementationRepair(data))) {
            handleImplementationFix(data, logger);
        } else if (decision.target() == RepairTarget.IMPLEMENTATION
                && findReferencedProductionFile(data.codes, data.pendingFixLog, data) != null) {
            repairImplementation(data, logger);
        } else if (decision.target() == RepairTarget.CONTRACT) {
            handleContractFix(data, logger);
        } else {
            handleFix(data, logger);
        }

        List<String> changedFiles = changedFiles(before, snapshotCodes(data.codes));
        boolean changed = !changedFiles.isEmpty();
        if (!changed) {
            // 修复没有产生实际变化时停止，避免下一轮再次发送相同上下文。
            data.noChangeStopCount++;
            data.repairStopRequested = true;
            data.shouldFix = false;
            data.markRepairStopped("NO_CHANGE");
            clearRepairBaseline(data);
        } else {
            data.repairCandidateChangedFiles = new ArrayList<>(changedFiles);
            // 任何源码修复都会使旧 Docker/Surefire 证据失效，必须重新验证后才能用于质量判定。
            invalidateVerificationEvidence(data);
            if (decision.target() == RepairTarget.IMPLEMENTATION
                    && data.currentSlice() != null
                    && !data.finalVerificationStarted
                    && data.currentSliceTestFiles.isEmpty()) {
                // 生产越界修复后回到正常测试生成节点，首次测试生成不额外消耗修复预算。
                data.resumeSliceTestGeneration = true;
            }
        }
        runJournalService.recordRepair(
                data, decision, changed, changedFiles,
                changed ? "修复已更新 " + changedFiles.size() + " 个文件。" : "修复没有产生实际文件变化。");
    }

    /**
     * 保存本次 LLM 修复前的源码和验证证据，供下一轮出现编译级退化时完整回滚。
     */
    private void captureRepairBaseline(SoftwareStudioWorkflowData data, Map<String, String> codes) {
        data.repairBaselineCodes = new LinkedHashMap<>(codes);
        data.repairCandidateChangedFiles = new ArrayList<>();
        data.repairBaselineValidationWarnings = new ArrayList<>(data.validationWarnings);
        data.repairBaselineQualityPolicyResult = data.qualityPolicyResult;
        data.repairBaselineVerificationResult = data.verificationResult;
        data.repairBaselineFailureKind = data.pendingFailureKind;
        data.repairBaselineFixLog = data.pendingFixLog;
        data.repairBaselineErrorType = data.pendingErrorType;
        data.repairBaselineExecutionResult = data.executionResult;
        data.repairBaselineTestResult = data.testResult;
        data.repairBaselineFailureFingerprint = data.lastFailureFingerprint;
    }

    /**
     * 无实际候选变更时清理快照，避免后续验证错误地回滚到过期状态。
     */
    private void clearRepairBaseline(SoftwareStudioWorkflowData data) {
        data.repairBaselineCodes.clear();
        data.repairCandidateChangedFiles.clear();
        data.repairBaselineValidationWarnings.clear();
        data.repairBaselineFailureKind = com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind.NONE;
        data.repairBaselineFixLog = null;
        data.repairBaselineErrorType = null;
        data.repairBaselineExecutionResult = null;
        data.repairBaselineTestResult = null;
        data.repairBaselineFailureFingerprint = null;
    }

    private void handleFix(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        // 修复阶段会把三类信息拼在一起：
        // 1. 当前代码上下文
        // 2. 编译/运行/测试日志
        // 3. 前面轻量校验阶段积累下来的结构警告
        // 这样 debuggerAgent 能同时看到“硬错误”和“软约束”。
        logger.accept("9. Invoking debugger agent for final repair.");

        ProjectContract activeContract = activeContract(data);
        String currentCodeContext = codeContextBuilderService.buildContractContext(activeContract)
                + "\n=== AVAILABLE SLICE STRUCTURE ===\n"
                + sliceProductionScopeService.activeStructure(data)
                + codeContextBuilderService.buildOptimizedCodeContext(
                        data.codes, data.pendingFixLog, data.pendingErrorType);
        String enhancedErrorLog = buildEnhancedErrorLog(
                data.pendingErrorType, data.pendingFixLog, data.codes, data.validationWarnings);

        CodeFixResult fixResult = debuggerAgent.analyzeAndFix(data.pendingErrorType, enhancedErrorLog, currentCodeContext);
        applyMultiFileFixes(data, logger, fixResult, "Debugger");
    }

    /**
     * 多文件编译和 Spring 失败交给精简的实现修复器，避免通用 Debugger 同时承担契约与测试知识。
     */
    private void handleImplementationFix(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        if (implementationRepairAgent == null) {
            handleFix(data, logger);
            return;
        }
        logger.accept("9. Invoking implementation repair agent with real tool evidence.");
        String context = codeContextBuilderService.buildOptimizedCodeContext(
                data.codes, data.pendingFixLog, data.pendingErrorType);
        CodeFixResult result = implementationRepairAgent.repair(data.pendingFixLog, context);
        applyMultiFileFixes(data, logger, result, "Implementation repair");
    }

    /**
     * 编译和测试已提供真实证据后，由契约专项修复器统一处理接口及前后端集成。
     */
    private void handleContractFix(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        if (contractRepairAgent == null) {
            handleFix(data, logger);
            return;
        }
        logger.accept("9. Invoking contract repair agent after real verification.");
        String context = codeContextBuilderService.buildContractContext(activeContract(data))
                + codeContextBuilderService.buildOptimizedCodeContext(
                        data.codes, data.pendingFixLog, data.pendingErrorType);
        CodeFixResult result = contractRepairAgent.repair(data.pendingFixLog, context);
        applyMultiFileFixes(data, logger, result, "Contract repair");
    }

    private void applyMultiFileFixes(
            SoftwareStudioWorkflowData data,
            Consumer<String> logger,
            CodeFixResult fixResult,
            String agentName) {
        List<CodeFix> fixes = fixResult == null ? null : fixResult.fixes();

        if (fixes == null || fixes.isEmpty()) {
            logger.accept(agentName + " agent did not return a concrete fix.");
            return;
        }

        // 默认只允许覆盖已经生成的文件；仅对契约门禁确认、且当前切片蓝图已声明的漏文件开放补建。
        Map<String, String> existingFiles = snapshotCodes(data.codes);
        List<CodeFix> normalizedFixes = new ArrayList<>();
        logger.accept(agentName + " agent returned " + fixes.size() + " fixes.");
        for (CodeFix fix : fixes) {
            if (fix == null) {
                logger.accept("   - 跳过空修复记录。");
                continue;
            }
            String normalizedFilename = sourceCodePathService.normalizeGeneratedFilename(fix.filename(), fix.newCode());
            String normalizedCode = sourceCodePathService.normalizeGeneratedCode(normalizedFilename, fix.newCode());
            // 整文件修复返回空内容时绝不能覆盖已有源码，否则一次无效修复会制造新的主编译错误。
            if (normalizedCode.isBlank()) {
                logger.accept("   - 跳过空白整文件修复: " + normalizedFilename);
                continue;
            }
            boolean explicitMissingTemplate = isExplicitMissingTemplateFix(
                    normalizedFilename, data.pendingFixLog, data.codes);
            boolean explicitMissingContractFrontend = isExplicitMissingContractFrontendFix(
                    data, normalizedFilename);
            boolean declaredMissingProduction = isDeclaredMissingProductionFix(
                    data, normalizedFilename, existingFiles);
            if (!existingFiles.containsKey(normalizedFilename)
                    && !explicitMissingTemplate && !explicitMissingContractFrontend
                    && !declaredMissingProduction) {
                logger.accept("   - 跳过修复阶段新增文件: " + normalizedFilename);
                continue;
            }
            boolean contractIntegrationPoint = isCurrentContractIntegrationRepair(
                    data, normalizedFilename, existingFiles);
            if (!data.canModifyInCurrentSlice(normalizedFilename)
                    && !explicitMissingTemplate && !explicitMissingContractFrontend
                    && !declaredMissingProduction && !contractIntegrationPoint
                    && !isDuplicateMappingOwnerRepair(data, normalizedFilename)) {
                logger.accept("   - 跳过越界修复文件: " + normalizedFilename);
                continue;
            }
            CodeFix normalizedFix = new CodeFix(normalizedFilename, fix.explanation(), normalizedCode);
            logger.accept("   - " + safeValue(fix.explanation(), "No explanation provided"));
            sourceCodePathService.applyCodeFix(data.codes, normalizedFilename, normalizedCode);
            normalizedFixes.add(normalizedFix);
        }
        if (data.projectPath != null) {
            workspaceService.applyFixesToDisk(Path.of(data.projectPath), normalizedFixes, logger);
        }
    }

    /**
     * 允许补建当前切片蓝图已经声明、但首次生成遗漏的生产文件。
     * 只有确定性契约门禁正在阻断时才开放，避免 Debugger 借修复阶段臆造额外配置或应用类型。
     */
    private boolean isDeclaredMissingProductionFix(
            SoftwareStudioWorkflowData data,
            String filename,
            Map<String, String> existingFiles) {
        if (data == null || filename == null || existingFiles == null) {
            return false;
        }
        String normalized = sourceCodePathService.normalizePath(filename);
        if (!normalized.startsWith("src/main/java/") || !normalized.endsWith(".java")
                || existingFiles.containsKey(normalized)) {
            return false;
        }
        // 最终验证针对完整项目，不能再把补建范围误限到最后停留的单个切片。
        boolean declaredByActiveScope = data.finalVerificationStarted
                ? data.structure != null && data.structure.files() != null
                        && data.structure.files().stream()
                                .filter(java.util.Objects::nonNull)
                                .map(file -> sourceCodePathService.normalizePath(file.targetPath()))
                                .anyMatch(normalized::equals)
                : data.currentSlice() != null && data.currentSlice().ownedFiles().stream()
                        .map(sourceCodePathService::normalizePath)
                        .anyMatch(normalized::equals);
        if (!declaredByActiveScope || data.qualityPolicyResult == null) {
            return false;
        }
        return !data.qualityPolicyResult.contractBlockingFindings().isEmpty();
    }

    /**
     * 契约明确声明且门禁确认缺失的前端源文件允许补建，避免修复结果被通用“禁止新增文件”规则丢弃。
     */
    private boolean isExplicitMissingContractFrontendFix(
            SoftwareStudioWorkflowData data, String filename) {
        if (data == null || filename == null || !sourceCodePathService.isFrontendFile(filename)) {
            return false;
        }
        ProjectContract contract = activeContract(data);
        if (contract == null || contract.frontendCalls() == null) {
            return false;
        }
        String normalized = sourceCodePathService.normalizePath(filename);
        boolean declared = contract.frontendCalls().stream()
                .anyMatch(call -> normalized.equals(sourceCodePathService.normalizePath(call.sourceFile())));
        if (!declared) {
            return false;
        }
        String expectedWarning = "Contract frontend call source file is missing: " + normalized;
        String fixLog = data.pendingFixLog == null ? "" : data.pendingFixLog;
        return fixLog.contains(expectedWarning)
                || data.validationWarnings.stream().anyMatch(warning -> warning != null
                        && warning.contains(expectedWarning));
    }

    /**
     * 重复路由修复必须允许同时修改告警明确点名的两个 Controller，否则只能改新切片文件而无法移除旧端点。
     */
    private boolean isDuplicateMappingOwnerRepair(SoftwareStudioWorkflowData data, String filename) {
        if (data == null || filename == null || data.validationWarnings == null) {
            return false;
        }
        return data.validationWarnings.stream()
                .filter(java.util.Objects::nonNull)
                .filter(warning -> warning.contains("DUPLICATE_CONTROLLER_MAPPING")
                        || warning.contains("Duplicate controller mapping"))
                .anyMatch(warning -> warning.contains(filename));
    }

    /**
     * 仅允许补充错误日志明确点名且被现有 Controller 返回的 Thymeleaf 模板。
     */
    private boolean isExplicitMissingTemplateFix(
            String filename, String failureLog, List<SourceCode> codes) {
        String normalized = sourceCodePathService.normalizePath(filename);
        if (!normalized.startsWith("src/main/resources/templates/") || !normalized.endsWith(".html")) {
            return false;
        }
        String viewName = Path.of(normalized).getFileName().toString().replaceFirst("\\.html$", "");
        String log = failureLog == null ? "" : failureLog.toLowerCase(java.util.Locale.ROOT);
        boolean explicitlyMissing = log.contains("resolving template [" + viewName.toLowerCase(java.util.Locale.ROOT) + "]")
                || log.contains("templates/" + viewName.toLowerCase(java.util.Locale.ROOT) + ".html is missing");
        if (!explicitlyMissing || codes == null) {
            return false;
        }
        String returnedView = "return \"" + viewName + "\"";
        return codes.stream().filter(java.util.Objects::nonNull)
                .map(SourceCode::code).filter(java.util.Objects::nonNull)
                .anyMatch(code -> code.contains(returnedView));
    }

    /**
     * 后续切片可扩展当前契约端点匹配的既有 Controller 及其直接 Service，回归安全由已验收测试保障。
     */
    private boolean isCurrentContractIntegrationRepair(
            SoftwareStudioWorkflowData data, String filename, Map<String, String> existingFiles) {
        if (data == null || data.currentSlice() == null || filename == null
                || !filename.startsWith("src/main/java/") || !existingFiles.containsKey(filename)) {
            return false;
        }
        ProjectContract contract = activeContract(data);
        if (contract == null || contract.endpoints() == null || contract.endpoints().isEmpty()) {
            return false;
        }
        String typeName = Path.of(filename).getFileName().toString().replaceFirst("\\.java$", "");
        if (filename.endsWith("Controller.java")) {
            return matchesCurrentContractController(typeName, existingFiles.get(filename), contract);
        }
        if (!filename.endsWith("Service.java")) {
            return false;
        }
        return existingFiles.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("Controller.java"))
                .filter(entry -> matchesCurrentContractController(
                        Path.of(entry.getKey()).getFileName().toString().replaceFirst("\\.java$", ""),
                        entry.getValue(), contract))
                .anyMatch(entry -> entry.getValue() != null && entry.getValue().contains(typeName));
    }

    private boolean matchesCurrentContractController(
            String typeName, String controllerCode, ProjectContract contract) {
        return contract.endpoints().stream().anyMatch(endpoint -> {
            String owner = endpoint.implementedBy() == null ? "" : endpoint.implementedBy();
            int ownerDot = owner.lastIndexOf('.');
            String ownerType = ownerDot >= 0 ? owner.substring(ownerDot + 1) : owner;
            if (typeName.equals(ownerType)) {
                return true;
            }
            String path = endpoint.path() == null ? "" : endpoint.path();
            int variable = path.indexOf("/{");
            String basePath = variable > 0 ? path.substring(0, variable) : path;
            return controllerCode != null && !basePath.isBlank()
                    && controllerCode.contains("@RequestMapping(\"" + basePath + "\")");
        });
    }

    private boolean isFutureTypeBoundaryFailure(String failureLog) {
        return failureLog != null && failureLog.contains("SLICE_FUTURE_TYPE_REFERENCE");
    }

    /**
     * 测试已经运行后暴露的实现错误通常涉及多个生产文件，需要当前切片级联修复。
     */
    private boolean requiresCrossFileImplementationRepair(SoftwareStudioWorkflowData data) {
        if (data == null) {
            return false;
        }
        if (data.pendingFailureKind == com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind.TEST_ASSERTION
                || data.pendingFailureKind
                == com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind.SPRING_CONTEXT) {
            return true;
        }
        if (data.pendingFailureKind != com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind.MAIN_COMPILE) {
            return false;
        }

        String normalizedLog = data.pendingFixLog == null ? "" : data.pendingFixLog
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", " ");
        // 多个报错文件或“调用方缺少成员”通常需要同步修改类型所有者，单文件 Developer 无法闭环。
        return referencedProductionFiles(data.codes, data.pendingFixLog, data).size() > 1
                || normalizedLog.contains("cannot find symbol")
                        && normalizedLog.contains("symbol: method")
                        && normalizedLog.contains("location:");
    }

    /**
     * 修复后清理旧编译和测试证据，防止源码已变化却沿用上一轮绿色 Surefire 结果。
     */
    private void invalidateVerificationEvidence(SoftwareStudioWorkflowData data) {
        data.verificationResult = com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationResult.empty();
        data.executionResult = "";
        data.testResult = "";
        data.currentSliceVerified = false;
        data.success = false;
    }

    /**
     * 测试编译或测试发现失败时复用 TestWriter 的一次调用，并消耗同一份修复预算。
     */
    private void regenerateTests(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        logger.accept("9. Regenerating tests for a test-owned failure.");
        TestClassesResult result = testWriterAgent.rewriteTests(
                data.prd,
                sliceTestScopeService.activeStructure(data),
                activeContract(data),
                codeContextBuilderService.buildCodeContextForTester(data.codes),
                data.pendingFixLog);
        if (result == null || result.testFiles() == null || result.testFiles().isEmpty()) {
            logger.accept("TestWriter did not return concrete test files.");
            return;
        }

        List<CodeFix> diskFixes = new ArrayList<>();
        java.util.Set<String> namedFailedTests = namedFailedTestFiles(data);
        for (SourceCode testFile : result.testFiles()) {
            if (testFile == null) {
                continue;
            }
            data.generatedTestFileCount++;
            TestScopeViolation violation = sliceTestScopeService.validate(data, testFile);
            if (violation != TestScopeViolation.NONE) {
                if (violation == TestScopeViolation.FUTURE_TYPE) {
                    data.skippedFutureTypeTestCount++;
                } else {
                    data.skippedFutureResourceTestCount++;
                }
                logger.accept("   跳过越界测试修复 (" + violation + "): " + testFile.filename());
                continue;
            }
            String filename = sourceCodePathService.normalizeGeneratedFilename(testFile.filename(), testFile.code());
            if (!namedFailedTests.isEmpty() && !namedFailedTests.contains(filename)) {
                logger.accept("   跳过未在失败日志中点名的绿色测试修复: " + filename);
                continue;
            }
            String normalizedCode = sourceCodePathService.normalizeGeneratedCode(filename, testFile.code());
            if (normalizedCode.isBlank()) {
                logger.accept("   跳过空白测试修复: " + filename);
                continue;
            }
            if (!data.canModifyInCurrentSlice(filename)) {
                data.skippedAcceptedTestFileCount++;
                continue;
            }
            sourceCodePathService.upsertSourceCode(data.codes, filename, normalizedCode);
            if (data.currentSlice() != null && !data.finalVerificationStarted
                    && !data.currentSliceTestFiles.contains(filename)) {
                data.currentSliceTestFiles.add(filename);
            }
            diskFixes.add(new CodeFix(filename, "TestWriter 按失败分类重新生成测试", normalizedCode));
            data.admittedTestFileCount++;
        }
        if (data.projectPath != null && !diskFixes.isEmpty()) {
            workspaceService.applyFixesToDisk(Path.of(data.projectPath), diskFixes, logger);
        }
    }

    /**
     * 从测试失败日志中解析已有测试文件；能精确定位时只允许 TestWriter 修改这些失败文件。
     */
    private java.util.Set<String> namedFailedTestFiles(SoftwareStudioWorkflowData data) {
        if (data == null || data.codes == null || data.pendingFixLog == null || data.pendingFixLog.isBlank()) {
            return java.util.Set.of();
        }
        java.util.LinkedHashSet<String> named = new java.util.LinkedHashSet<>();
        for (SourceCode source : data.codes) {
            if (source == null) {
                continue;
            }
            String filename = sourceCodePathService.normalizeGeneratedFilename(source.filename(), source.code());
            if (!filename.startsWith("src/test/java/") || !filename.endsWith("Test.java")) {
                continue;
            }
            String fileName = Path.of(filename).getFileName().toString();
            String className = fileName.replaceFirst("\\.java$", "");
            if (data.pendingFixLog.contains(filename)
                    || data.pendingFixLog.contains(filename.replace('/', '\\'))
                    || data.pendingFixLog.contains(fileName)
                    || data.pendingFixLog.matches("(?s).*\\b"
                            + java.util.regex.Pattern.quote(className) + "\\b.*")) {
                named.add(filename);
            }
        }
        return java.util.Set.copyOf(named);
    }

    /**
     * 编译日志能稳定定位生产文件时交给 Developer 单文件修复，避免 Debugger 无边界修改多处代码。
     */
    private void repairImplementation(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        SourceCode target = findReferencedProductionFile(data.codes, data.pendingFixLog, data);
        if (target == null) {
            return;
        }
        String filename = sourceCodePathService.normalizeGeneratedFilename(target.filename(), target.code());
        logger.accept("9. Routing implementation-owned failure to Developer: " + filename);
        SourceCode repaired = developerAgent.writeCode(
                data.prd,
                sliceProductionScopeService.activeStructure(data),
                activeContract(data),
                codeContextBuilderService.buildOptimizedCodeContext(
                        data.codes, data.pendingFixLog, data.pendingErrorType),
                filename,
                "Repair the referenced production file for this failure: " + data.pendingFixLog,
                "Preserve existing public contracts and implement the complete corrected file. "
                        + "Treat the compiler diagnostic as authoritative and copy every referenced method, constructor, "
                        + "generic, and nested DTO type exactly from the implemented code context.",
                "Failure repair; modify only the target file, do not invent framework APIs, overloads, or substitute DTOs.");
        if (repaired == null || repaired.code() == null || repaired.code().isBlank()) {
            logger.accept("Developer did not return a concrete implementation fix.");
            return;
        }
        String normalizedCode = sourceCodePathService.normalizeGeneratedCode(filename, repaired.code());
        sourceCodePathService.upsertSourceCode(data.codes, filename, normalizedCode);
        if (data.projectPath != null) {
            workspaceService.applyFixesToDisk(
                    Path.of(data.projectPath),
                    List.of(new CodeFix(filename, "Developer 按实现失败分类修复", normalizedCode)),
                    logger);
        }
    }

    private SourceCode findReferencedProductionFile(
            List<SourceCode> codes, String failureLog, SoftwareStudioWorkflowData data) {
        SourceCode jacksonOwner = findJacksonConstructionOwner(codes, failureLog, data);
        if (jacksonOwner != null) {
            return jacksonOwner;
        }
        return referencedProductionFiles(codes, failureLog, data).stream().findFirst().orElse(null);
    }

    /**
     * 从 Jackson 构造异常中提取被反序列化类型，优先修复真正的 DTO/模型所有者。
     */
    private SourceCode findJacksonConstructionOwner(
            List<SourceCode> codes, String failureLog, SoftwareStudioWorkflowData data) {
        if (codes == null || failureLog == null || !failureLog.contains("Cannot construct instance of")) {
            return null;
        }
        for (SourceCode code : codes) {
            if (code == null) {
                continue;
            }
            String filename = sourceCodePathService.normalizeGeneratedFilename(code.filename(), code.code());
            if (!filename.startsWith("src/main/java/")
                    || data != null && !data.canModifyInCurrentSlice(filename)) {
                continue;
            }
            String typeName = Path.of(filename).getFileName().toString().replaceFirst("\\.java$", "");
            if (failureLog.matches("(?s).*Cannot construct instance of [`']?[^`'\\s]*\\b"
                    + java.util.regex.Pattern.quote(typeName) + "[`']?.*")) {
                return code;
            }
        }
        return null;
    }

    /**
     * 提取编译日志明确点名且当前切片允许修改的生产文件，供单文件与多文件修复路由共同使用。
     */
    private List<SourceCode> referencedProductionFiles(
            List<SourceCode> codes, String failureLog, SoftwareStudioWorkflowData data) {
        if (codes == null || failureLog == null || failureLog.isBlank()) {
            return List.of();
        }
        List<SourceCode> referenced = new ArrayList<>();
        for (SourceCode code : codes) {
            if (code == null) {
                continue;
            }
            String filename = sourceCodePathService.normalizeGeneratedFilename(code.filename(), code.code());
            if (data != null && !data.canModifyInCurrentSlice(filename)) {
                continue;
            }
            if (!filename.startsWith("src/main/") || sourceCodePathService.isFrontendFile(filename)) {
                continue;
            }
            String pureName = Path.of(filename).getFileName().toString();
            if (failureLog.contains(filename) || failureLog.contains(filename.replace('/', '\\'))
                    || failureLog.contains(pureName)) {
                referenced.add(code);
            }
        }
        return List.copyOf(referenced);
    }

    /**
     * 切片阶段只向修复 Agent 提供当前切片契约，最终验证阶段才恢复完整项目契约。
     */
    private ProjectContract activeContract(SoftwareStudioWorkflowData data) {
        return data.currentSlice() == null || data.finalVerificationStarted
                ? data.contract
                : data.currentSlice().contract();
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
