package com.core.multiAgentSoftwareStudio.Model.Workflow;

/**
 * 区分运行日志中可回放的关键业务事件。
 */
public enum WorkflowJournalEventType {
    SLICE_STARTED,
    SLICE_ACCEPTED,
    SLICE_REGRESSION_FAILED,
    PREFLIGHT,
    BUILD_VERIFICATION,
    TEST_VERIFICATION,
    REPAIR
}
