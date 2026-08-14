package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Model.Tool.ToolChatMessage;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;

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

    public void incrementModelTurns() { modelTurns++; }
    public void incrementToolCalls() { toolCalls++; }

    public void recordWrite(String path) {
        changedFiles.add(path);
        writeVersion++;
    }

    public void recordCompilePassed() { compiledVersion = writeVersion; }
    public void recordTestsPassed() { testedVersion = writeVersion; }
    public void recordContractPassed() { contractValidatedVersion = writeVersion; }
    public boolean compileCurrent() { return compiledVersion == writeVersion; }
    public boolean testsCurrent() { return testedVersion == writeVersion; }
    public boolean contractCurrent() { return contractValidatedVersion == writeVersion; }

    public void complete(String summary) {
        completed = true;
        completionSummary = summary == null ? "" : summary;
    }

    public void block(String reason) {
        blocked = true;
        completionSummary = reason == null ? "" : reason;
    }
}
