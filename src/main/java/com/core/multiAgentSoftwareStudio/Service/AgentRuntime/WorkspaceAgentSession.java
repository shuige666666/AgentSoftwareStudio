package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Model.Tool.ToolChatMessage;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 保存一次工具 Agent 的真实工作区、消息历史、文件快照和验证状态。
 */
public final class WorkspaceAgentSession {
    private final Path projectRoot;
    private final WorkspaceAgentMode mode;
    private final SoftwareStudioWorkflowData workflowData;
    private final Consumer<String> logger;
    private final Map<String, String> baselineFiles;
    private final Set<String> allowedWritePaths;
    private final List<ToolChatMessage> messages = new ArrayList<>();
    private final Set<String> changedFiles = new LinkedHashSet<>();
    private int modelTurns;
    private int toolCalls;
    private int writeVersion;
    private int compiledVersion = -1;
    private int testedVersion = -1;
    private int contractValidatedVersion = -1;
    private boolean completed;
    private boolean blocked;
    private String completionSummary = "";
    private DockerSandboxService.WorkspaceShell shell;
    private String lastCheckpointHash = "";
    private boolean lastCheckpointPassed;
    private boolean finalCheckpointPassed;
    private String unresolvedVerificationFailure = "";
    private int unresolvedFailureVersion = -1;
    private String unresolvedBehaviorFailure = "";
    private int behaviorFailureVersion = -1;
    private String unresolvedFinalFailure = "";
    private int finalFailureVersion = -1;
    private int lastWriteTurn;
    private int lastValidationTurn;
    private int lastValidatedVersion = -1;
    private int automaticChecks;
    private CheckpointObservation checkpointObservation;

    public WorkspaceAgentSession(
            Path projectRoot,
            WorkspaceAgentMode mode,
            SoftwareStudioWorkflowData workflowData,
            Consumer<String> logger,
            Map<String, String> baselineFiles,
            Set<String> allowedWritePaths) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.mode = mode;
        this.workflowData = workflowData;
        this.logger = logger == null ? message -> { } : logger;
        this.baselineFiles = new LinkedHashMap<>(baselineFiles == null ? Map.of() : baselineFiles);
        this.allowedWritePaths = new LinkedHashSet<>(allowedWritePaths == null ? Set.of() : allowedWritePaths);
        if (mode == WorkspaceAgentMode.DEVELOPMENT) {
            // 中断续接沿用此前已消耗的全局预算，不为新会话重新发放轮次。
            this.modelTurns = workflowData.developmentModelTurnsUsed;
            this.toolCalls = workflowData.developmentToolCallsUsed;
        }
        this.lastValidationTurn = modelTurns;
    }

    public Path projectRoot() { return projectRoot; }
    public WorkspaceAgentMode mode() { return mode; }
    public SoftwareStudioWorkflowData workflowData() { return workflowData; }
    public Consumer<String> logger() { return logger; }
    public Map<String, String> baselineFiles() { return baselineFiles; }
    public Set<String> allowedWritePaths() { return allowedWritePaths; }
    public List<ToolChatMessage> messages() { return messages; }
    public Set<String> changedFiles() { return changedFiles; }
    public int modelTurns() { return modelTurns; }
    public int toolCalls() { return toolCalls; }
    public int writeVersion() { return writeVersion; }
    public boolean completed() { return completed; }
    public boolean blocked() { return blocked; }
    public String completionSummary() { return completionSummary; }
    public String lastCheckpointHash() { return lastCheckpointHash; }
    public boolean lastCheckpointPassed() { return lastCheckpointPassed; }
    public boolean finalCheckpointPassed() { return finalCheckpointPassed; }

    public DockerSandboxService.WorkspaceShell shell() { return shell; }
    public void setShell(DockerSandboxService.WorkspaceShell shell) { this.shell = shell; }

    /** 正式验证前清理会话容器，阻止后台进程继续写候选目录。 */
    public void closeShell(DockerSandboxService sandbox) {
        if (shell != null) sandbox.closeWorkspaceShell(shell);
        shell = null;
    }

    public void incrementModelTurns() {
        modelTurns++;
        if (mode == WorkspaceAgentMode.DEVELOPMENT) workflowData.developmentModelTurnsUsed = modelTurns;
    }
    public void incrementToolCalls() {
        toolCalls++;
        if (mode == WorkspaceAgentMode.DEVELOPMENT) workflowData.developmentToolCallsUsed = toolCalls;
    }

    public void recordWrite(String path) {
        changedFiles.add(path);
        writeVersion++;
        lastWriteTurn = modelTurns;
        lastCheckpointPassed = false;
        finalCheckpointPassed = false;
    }

    public void recordCheckpoint(String hash, boolean passed, boolean finalScope) {
        lastCheckpointHash = hash == null ? "" : hash;
        lastCheckpointPassed = passed;
        finalCheckpointPassed = passed && finalScope;
        lastValidationTurn = modelTurns;
        lastValidatedVersion = writeVersion;
    }

    public void recordCompilePassed() { compiledVersion = writeVersion; }
    public void recordTestsPassed() {
        testedVersion = writeVersion;
        clearVerificationFailure();
    }
    public void recordContractPassed() { contractValidatedVersion = writeVersion; }
    public boolean compileCurrent() { return compiledVersion == writeVersion; }
    public boolean testsCurrent() { return testedVersion == writeVersion; }
    public boolean contractCurrent() { return contractValidatedVersion == writeVersion; }

    /** 保留最近未被成功测试清除的失败，供压缩后的开发会话继续定位。 */
    public void recordVerificationFailure(String failure) {
        unresolvedVerificationFailure = failure == null ? "" : failure;
        unresolvedFailureVersion = writeVersion;
    }

    public void clearVerificationFailure() {
        unresolvedVerificationFailure = "";
        unresolvedFailureVersion = -1;
    }

    public String unresolvedVerificationFailure() {
        return String.join("\n", verificationFailures());
    }
    /** 单独提供源码验证失败，压缩时不能与业务失败共用一个首尾截断窗口。 */
    public String sourceVerificationFailure() { return unresolvedVerificationFailure; }

    /** 单独提供独立业务失败，普通自测通过不会清除这份诊断。 */
    public String behaviorVerificationFailure() { return unresolvedBehaviorFailure; }
    public String finalVerificationFailure() { return unresolvedFinalFailure; }
    CheckpointObservation checkpointObservation() { return checkpointObservation; }

    /** 保存正式快照的各范围结论；只作诊断对照，不恢复验收通过或完成状态。 */
    void recordCheckpointObservation(CheckpointObservation observation) {
        checkpointObservation = observation;
    }
    public int unresolvedFailureVersion() {
        return Math.max(unresolvedFailureVersion, Math.max(behaviorFailureVersion, finalFailureVersion));
    }

    /** 完整契约和交付资料只由下一次 final 复验更新，中途绿色不能代表这些问题已解决。 */
    public void recordFinalFeedback(String failure) {
        unresolvedFinalFailure = failure == null ? "" : failure;
        finalFailureVersion = unresolvedFinalFailure.isBlank() ? -1 : writeVersion;
    }

    /** 各类诊断分别进入摘要，避免某一份长日志遮住其他验收范围。 */
    public List<String> verificationFailures() {
        return java.util.stream.Stream.of(unresolvedVerificationFailure, unresolvedFinalFailure, unresolvedBehaviorFailure)
                .filter(value -> !value.isBlank()).toList();
    }

    /** 记录各检查实际覆盖的快照和范围，成功业务仅代表本次已执行的有限检查。 */
    record CheckpointObservation(int writeVersion, String sourceHash, String scope, String status,
                                 String buildStatus, String testStatus, boolean testSummaryPresent,
                                 int testsRun, int failures, int errors, int skipped,
                                 String behaviorStatus, int behaviorPassed, int behaviorUnresolved,
                                 String reportPath) { }

    /** 续接保留诊断和受检事实；通过状态仍由新会话的实际复验确定。 */
    record VerificationFeedback(int writeVersion, String checkpointHash, String sourceFailure, int sourceVersion,
                                String finalFailure, int finalVersion, String behaviorFailure, int behaviorVersion,
                                CheckpointObservation checkpointObservation) { }

    VerificationFeedback verificationFeedback() {
        return new VerificationFeedback(writeVersion, lastCheckpointHash, unresolvedVerificationFailure,
                unresolvedFailureVersion, unresolvedFinalFailure, finalFailureVersion,
                unresolvedBehaviorFailure, behaviorFailureVersion, checkpointObservation);
    }

    /** 源码和预算指纹校验由调用方负责，此处仅恢复未完成的诊断。 */
    void restoreVerificationFeedback(VerificationFeedback feedback) {
        if (feedback == null) return;
        writeVersion = feedback.writeVersion();
        lastCheckpointHash = feedback.checkpointHash();
        unresolvedVerificationFailure = feedback.sourceFailure();
        unresolvedFailureVersion = feedback.sourceVersion();
        unresolvedFinalFailure = feedback.finalFailure();
        finalFailureVersion = feedback.finalVersion();
        unresolvedBehaviorFailure = feedback.behaviorFailure();
        behaviorFailureVersion = feedback.behaviorVersion();
        // 旧v2缺少这项可选事实；不能凭缺字段编造历史绿色。
        checkpointObservation = feedback.checkpointObservation();
    }

    /** 业务失败只能由下一次独立行为检查更新，生成测试绿色不能清除它。 */
    public void recordBehaviorFeedback(String failure) {
        unresolvedBehaviorFailure = failure == null ? "" : failure;
        behaviorFailureVersion = unresolvedBehaviorFailure.isBlank() ? -1 : writeVersion;
    }

    /** 在合法消息边界判断是否需要平台验证，同一未修改版本只验证一次。 */
    public boolean validationDue(com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentLimitsConfig.ValidationPolicy policy,
                                 boolean closing) {
        return policy.isEnabled() && automaticChecks < policy.getMaxAutomaticChecks()
                && writeVersion != lastValidatedVersion
                && (closing || modelTurns - lastValidationTurn >= policy.getIntervalTurns()
                    && (modelTurns - lastWriteTurn >= policy.getQuietTurns()
                        || modelTurns - lastValidationTurn >= policy.getIntervalTurns() * 2));
    }

    public void recordAutomaticCheck() { automaticChecks++; }

    public void complete(String summary) {
        completed = true;
        completionSummary = summary == null ? "" : summary;
    }

    public void block(String reason) {
        blocked = true;
        completionSummary = reason == null ? "" : reason;
    }
}
