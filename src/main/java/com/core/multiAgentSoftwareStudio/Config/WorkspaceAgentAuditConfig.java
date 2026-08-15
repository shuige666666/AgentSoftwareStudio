package com.core.multiAgentSoftwareStudio.Config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 配置工具 Agent 的持久化审计日志，在可诊断性和日志体积之间提供明确边界。
 */
@Component
@ConfigurationProperties(prefix = "studio.agent-runtime.audit")
public class WorkspaceAgentAuditConfig {

    private boolean enabled = true;
    private String rootDirectory = "studio-run-logs";
    private int maxPromptChars = 200_000;
    private int maxArgumentChars = 200_000;
    private int maxResultChars = 32_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getRootDirectory() { return rootDirectory; }
    public void setRootDirectory(String rootDirectory) {
        if (rootDirectory != null && !rootDirectory.isBlank()) {
            this.rootDirectory = rootDirectory;
        }
    }
    public int getMaxPromptChars() { return maxPromptChars; }
    public void setMaxPromptChars(int maxPromptChars) { this.maxPromptChars = requirePositive(maxPromptChars); }
    public int getMaxArgumentChars() { return maxArgumentChars; }
    public void setMaxArgumentChars(int maxArgumentChars) { this.maxArgumentChars = requirePositive(maxArgumentChars); }
    public int getMaxResultChars() { return maxResultChars; }
    public void setMaxResultChars(int maxResultChars) { this.maxResultChars = requirePositive(maxResultChars); }

    private int requirePositive(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("Workspace Agent audit limits must be positive");
        }
        return value;
    }
}
