package com.core.multiAgentSoftwareStudio.Model.Workflow;

/**
 * 工作流异常中保留已经创建的候选项目路径，供外部基准继续诊断。
 */
public class WorkflowExecutionFailure extends RuntimeException {
    private final String projectPath;

    public WorkflowExecutionFailure(String projectPath, RuntimeException cause) {
        super(cause);
        this.projectPath = projectPath;
    }

    public String projectPath() {
        return projectPath;
    }
}
