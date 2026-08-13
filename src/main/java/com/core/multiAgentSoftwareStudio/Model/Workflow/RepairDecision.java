package com.core.multiAgentSoftwareStudio.Model.Workflow;

import java.io.Serializable;

/**
 * 保存失败分流后的目标、是否允许调用 LLM 以及停止原因。
 */
public record RepairDecision(
        RepairTarget target,
        boolean retryable,
        boolean llmAllowed,
        String failureFingerprint,
        String reason) implements Serializable {

    private static final long serialVersionUID = 1L;
}
