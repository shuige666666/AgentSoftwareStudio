package com.core.multiAgentSoftwareStudio.Service.Generation;

import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;

/** 在初次开发和上下文压缩后生成同一份当前任务说明。 */
public final class DevelopmentMilestoneObjective {

    private DevelopmentMilestoneObjective() { }

    /** 提供完整目标信息，任务建议不能隐藏后续能力的契约。 */
    public static String projectContext(SoftwareStudioWorkflowData data) {
        return "Original user request:\n" + data.userRequest + "\n\nProduct requirements:\n" + data.prd
                + "\n\nArchitecture and file responsibilities:\n" + data.structure
                + "\n\nRequired cross-layer contract:\n" + data.contract + "\n\n";
    }

    /** 单任务也交付保存的失败证据；新验证完成后可移出旧诊断，避免续接只看到原始需求。 */
    public static String renderSingle(SoftwareStudioWorkflowData data, boolean includePreviousFailure) {
        String objective = projectContext(data) + """
                Implement and verify the complete requested project in the current workspace.

                Inspect the real workspace and its already compiling scaffold before editing. Keep the existing
                build configuration and application bootstrap unless a concrete business need requires a change.
                Create or update business code, frontend, meaningful behavior tests and README in this same session.
                Keep controller, service, persistence, DTO, template, JavaScript and CSS contracts consistent.
                Use bash for focused diagnosis; submit_checkpoint(scope=progress) for full build and all existing tests.
                Resolve failures in this session, then submit_checkpoint(scope=final) for completion.
                """;
        if (includePreviousFailure && data.milestoneState != null && !data.milestoneState.lastFailure().isBlank()) {
            objective += "\nPrevious interruption evidence; revalidate against current source:\n"
                    + data.milestoneState.lastFailure() + "\n";
        }
        return objective;
    }

    /** 根据持久化任务状态重建当前目标，避免压缩后沿用已经完成的任务。 */
    public static String render(SoftwareStudioWorkflowData data) {
        return render(data, true);
    }

    /** 已收到本会话验证时，历史中断诊断由当前权威反馈替代，完整目标仍保留。 */
    public static String render(SoftwareStudioWorkflowData data, boolean includePreviousFailure) {
        StringBuilder objective = new StringBuilder(projectContext(data)).append("""
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
                """).append("\n\nBusiness milestones:\n");
        data.milestoneState.tasks().forEach(task -> objective.append("- ").append(task.id())
                .append(": ").append(task.goal())
                .append(task.accepted() ? " [accepted at " + task.sourceHash() + "]" : " [pending]")
                .append(" criteria=").append(task.acceptanceCriteria())
                .append('\n'));
        objective.append("Use read_planning(slice-plan.json) for each task's contract mapping; the full project contract is above.\n");
        var current = data.milestoneState.current();
        if (current != null) {
            objective.append("\nSuggested next milestone: ").append(current.id()).append('\n');
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
