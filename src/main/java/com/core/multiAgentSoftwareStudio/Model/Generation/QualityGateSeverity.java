package com.core.multiAgentSoftwareStudio.Model.Generation;

/**
 * 区分必须阻断工作流的问题与仅用于诊断的提示。
 */
public enum QualityGateSeverity {
    BLOCK,
    NEEDS_REVIEW,
    INFO
}
