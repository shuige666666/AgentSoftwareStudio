package com.core.multiAgentSoftwareStudio.Model.Generation;

import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;

import java.io.Serializable;

/**
 * 保存一项前置质量门禁的稳定标识、严重级别和诊断证据。
 */
public record QualityPolicyFinding(
        String gate,
        QualityGateSeverity severity,
        FailureKind failureKind,
        String evidence) implements Serializable {

    private static final long serialVersionUID = 1L;
}
