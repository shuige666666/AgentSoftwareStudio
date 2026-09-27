package com.core.multiAgentSoftwareStudio.Service.Generation;

import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;

/** 在初次开发和上下文压缩后生成同一份当前任务说明。 */
public final class DevelopmentMilestoneObjective {

    private DevelopmentMilestoneObjective() { }

    /** 根据持久化任务状态重建当前目标，避免压缩后沿用已经完成的任务。 */
    public static String render(SoftwareStudioWorkflowData data) {
        StringBuilder objective = new StringBuilder("""
                Implement the requested project through the ordered business milestones below.
                Work in one continuous development session with one global model/tool budget.
                Inspect relevant source files before editing. The current milestone context is included below.
                Each milestone may change multiple layers and must keep all earlier required behavior.
                Implement and test the current milestone first. Defer later endpoints, pages and tests until
                its progress checkpoint passes; create only shared types needed to keep the current code building.
                Group related source changes into multi-file patches where practical, then run focused tests.
                Do not spend the remaining turns writing unrelated future files or polishing the whole project
                before requesting the current milestone checkpoint.
                After implementing and testing the current milestone, call submit_checkpoint with
                scope=progress and its milestoneId. A passing response gives the next milestone.
                After every milestone is accepted, call submit_checkpoint with scope=final.
                Do not claim a capability is complete from a build alone; add meaningful behavior tests.

                Original user request:
                """).append(data.userRequest)
                .append("\n\nBusiness milestones (future items are context, not work for this checkpoint):\n");
        data.milestoneState.tasks().forEach(task -> objective.append("- ").append(task.id())
                .append(": ").append(task.goal())
                .append(task.accepted() ? " [accepted at " + task.sourceHash() + "]" : " [pending]")
                .append('\n'));
        var current = data.milestoneState.current();
        if (current != null) {
            objective.append("\nCurrent milestone: ").append(current.id()).append('\n');
            objective.append("Current acceptance criteria: ").append(current.acceptanceCriteria()).append('\n');
            data.sliceDeliveryPlan.slices().stream().filter(slice -> slice.id().equals(current.id()))
                    .findFirst().ifPresent(slice -> objective.append("Current milestone contract:\n")
                            .append(slice.contract()).append('\n'));
        }
        if (!data.milestoneState.lastFailure().isBlank()) {
            objective.append("Last failure: ").append(data.milestoneState.lastFailure()).append('\n');
        }
        return objective.toString();
    }
}
