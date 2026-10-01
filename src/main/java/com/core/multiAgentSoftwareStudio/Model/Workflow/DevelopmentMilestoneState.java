package com.core.multiAgentSoftwareStudio.Model.Workflow;

import java.io.Serializable;
import java.util.List;

/** 保存当前 run 的业务能力进度、验证版本和跨进程续接所需的全局预算。 */
public record DevelopmentMilestoneState(int schemaVersion, List<Task> tasks,
                                        int modelTurnsUsed, int toolCallsUsed,
                                        String lastFailure, DevelopmentMilestonePolicy policy,
                                        String latestAcceptedId) implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final int CURRENT_SCHEMA_VERSION = 2;

    /** 旧状态没有策略字段，继续按顺序门禁解释。 */
    public DevelopmentMilestoneState(int schemaVersion, List<Task> tasks, int modelTurnsUsed,
                                     int toolCallsUsed, String lastFailure) {
        this(schemaVersion, tasks, modelTurnsUsed, toolCallsUsed, lastFailure,
                DevelopmentMilestonePolicy.ORDERED, "");
    }

    public DevelopmentMilestoneState {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        lastFailure = lastFailure == null ? "" : lastFailure;
        policy = policy == null ? DevelopmentMilestonePolicy.ORDERED : policy;
        latestAcceptedId = latestAcceptedId == null ? "" : latestAcceptedId;
    }

    /** 一个能力只有对应通过的源码快照时才标为已接受。 */
    public record Task(String id, String goal, List<String> acceptanceCriteria,
                       boolean accepted, String sourceHash, String snapshotPath,
                       String checkpointReportPath) implements Serializable {
        private static final long serialVersionUID = 1L;

        public Task {
            acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
            sourceHash = sourceHash == null ? "" : sourceHash;
            snapshotPath = snapshotPath == null ? "" : snapshotPath;
            checkpointReportPath = checkpointReportPath == null ? "" : checkpointReportPath;
        }
    }

    public boolean active() { return tasks.size() > 1; }

    public Task current() {
        return tasks.stream().filter(task -> !task.accepted()).findFirst().orElse(null);
    }

    public Task lastAccepted() {
        // 建议式允许乱序接受，恢复版本必须取实际最近的 checkpoint。
        if (!latestAcceptedId.isBlank()) {
            return tasks.stream().filter(task -> task.accepted() && task.id().equals(latestAcceptedId))
                    .findFirst().orElse(null);
        }
        for (int index = tasks.size() - 1; index >= 0; index--) {
            if (tasks.get(index).accepted()) return tasks.get(index);
        }
        return null;
    }

    public boolean allAccepted() { return !tasks.isEmpty() && current() == null; }
}
