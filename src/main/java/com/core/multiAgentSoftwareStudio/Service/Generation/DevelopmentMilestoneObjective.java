package com.core.multiAgentSoftwareStudio.Service.Generation;

import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;

/** 在初次开发和上下文压缩后生成同一份当前任务说明。 */
public final class DevelopmentMilestoneObjective {

    private DevelopmentMilestoneObjective() { }

    /** 所有推进策略共享完整目标信息，对照时只改变控制方式。 */
    public static String projectContext(SoftwareStudioWorkflowData data) {
        return "Original user request:\n" + data.userRequest + "\n\nProduct requirements:\n" + data.prd
                + "\n\nArchitecture and file responsibilities:\n" + data.structure
                + "\n\nRequired cross-layer contract:\n" + data.contract + "\n\n";
    }

    /** 根据持久化任务状态重建当前目标，避免压缩后沿用已经完成的任务。 */
    public static String render(SoftwareStudioWorkflowData data) {
        return render(data, true);
    }

    /** 已收到本会话验证时，历史中断诊断由当前权威反馈替代，完整目标仍保留。 */
    public static String render(SoftwareStudioWorkflowData data, boolean includePreviousFailure) {
        boolean advisory = data.milestoneState.policy() ==
                com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestonePolicy.ADVISORY;
        StringBuilder objective = new StringBuilder(projectContext(data)).append(advisory ? """
                Implement and verify the complete project. Business milestones are advisory planning aids.
                Use one continuous development session and one global model/tool budget.
                You may combine tasks, change their implementation order, and implement shared code.
                Inspect source before editing and add meaningful behavior tests. Preserve required behavior.
                For diagnostic validation call submit_checkpoint(scope=progress) without milestone IDs.
                To request acceptance of implemented tasks, use milestoneId or milestoneIds (an array of IDs).
                Acceptance requires a passing checkpoint of the selected and already accepted contracts.
                Request submit_checkpoint(scope=final) whenever the whole project is ready, even if individual
                milestone acceptance records are missing. Final still requires the full contract, all tests,
                and bound independent public behavior checks. Never infer completion from a build alone.
                """ : """
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

                """).append("\n\nBusiness milestones:\n");
        data.milestoneState.tasks().forEach(task -> objective.append("- ").append(task.id())
                .append(": ").append(task.goal())
                .append(task.accepted() ? " [accepted at " + task.sourceHash() + "]" : " [pending]")
                .append(" criteria=").append(task.acceptanceCriteria())
                .append('\n'));
        objective.append("Use read_planning(slice-plan.json) for each task's contract mapping; the full project contract is above.\n");
        var current = data.milestoneState.current();
        if (current != null) {
            objective.append(advisory ? "\nSuggested next milestone: " : "\nCurrent milestone: ").append(current.id()).append('\n');
            objective.append("Current acceptance criteria: ").append(current.acceptanceCriteria()).append('\n');
            data.sliceDeliveryPlan.slices().stream().filter(slice -> slice.id().equals(current.id()))
                    .findFirst().ifPresent(slice -> appendCurrentContract(objective, slice.contract(), data.contract));
        }
        if (includePreviousFailure && !data.milestoneState.lastFailure().isBlank()) {
            objective.append("Last failure: ").append(data.milestoneState.lastFailure()).append('\n');
        }
        return objective.toString();
    }

    /** 总契约已提供相同细节时只列当前范围；任务独有约定仍完整展开，不能因去重丢失规则。 */
    private static void appendCurrentContract(StringBuilder objective,
            com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract current,
            com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract full) {
        objective.append("Current milestone contract (matching details are in the full contract above):\n");
        if (full == null || current == null) {
            objective.append(current).append('\n');
            return;
        }
        current.endpoints().forEach(endpoint -> objective.append("- endpoint: ")
                .append(full.endpoints().contains(endpoint) ? endpoint.method() + " " + endpoint.path() : endpoint).append('\n'));
        current.views().forEach(view -> objective.append("- view: ")
                .append(full.views().contains(view) ? view.name() + " -> " + view.templatePath() : view).append('\n'));
        current.frontendCalls().forEach(call -> objective.append("- frontend call: ")
                .append(full.frontendCalls().contains(call) ? call.sourceFile() + " " + call.method() + " " + call.path() : call).append('\n'));
        current.additionalFiles().forEach(file -> objective.append("- file: ")
                .append(full.additionalFiles().contains(file) ? file.targetPath() : file).append('\n'));
    }
}
