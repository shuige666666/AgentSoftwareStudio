package com.core.multiAgentSoftwareStudio.Service;

import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowService;
import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowExecutionResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * 软件工坊对外入口服务，负责接收生成请求并委托工作流服务执行完整流程。
 */
@Service
public class SoftwareStudioService {

    private final SoftwareStudioWorkflowService workflowService;

    /**
     * 注入软件工坊工作流服务。
     */
    public SoftwareStudioService(SoftwareStudioWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /**
     * 同步执行项目生成流程并返回最终代码结果
     */
    public List<SourceCode> generateProject(String userRequest) {
        return workflowService.generateProject(userRequest, System.out::println);
    }

    /**
     * 以流式日志回调的方式执行项目生成流程
     */
    public WorkflowExecutionResult generateProjectStream(String userRequest, Consumer<String> eventListener) {
        return workflowService.generateProjectWithResult(userRequest, log -> {
            System.out.println(log);
            if (eventListener != null) {
                eventListener.accept(log);
            }
        });
    }
}
