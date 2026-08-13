package com.core.multiAgentSoftwareStudio.Model.Workflow;

import java.io.Serializable;

/**
 * 保存一次沙箱命令的原始执行证据，避免只返回无法可靠判断的日志字符串。
 */
public record SandboxExecutionResult(
        String command,
        Integer exitCode,
        String output,
        long durationMillis,
        boolean timedOut,
        String infrastructureError) implements Serializable {

    private static final long serialVersionUID = 1L;

    public SandboxExecutionResult {
        command = command == null ? "" : command;
        output = output == null ? "" : output;
    }

    public boolean completedSuccessfully() {
        return !timedOut && infrastructureError == null && exitCode != null && exitCode == 0;
    }
}
