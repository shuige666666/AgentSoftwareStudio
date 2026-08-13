package com.core.multiAgentSoftwareStudio.Model.Workflow;

import java.io.Serializable;
import java.util.List;

/**
 * 记录一次门禁、验证或修复的必要证据，供失败回放和聚合统计使用。
 */
public record WorkflowJournalEntry(
        int sequence,
        String timestamp,
        WorkflowJournalEventType eventType,
        String sliceId,
        int attempt,
        FailureKind failureKind,
        RepairTarget repairTarget,
        boolean successful,
        String failureFingerprint,
        List<String> changedFiles,
        List<String> gateIds,
        String evidence) implements Serializable {

    private static final long serialVersionUID = 1L;

    public WorkflowJournalEntry {
        changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
        gateIds = gateIds == null ? List.of() : List.copyOf(gateIds);
        evidence = evidence == null ? "" : evidence;
        sliceId = sliceId == null ? "UNSCOPED" : sliceId;
    }
}
