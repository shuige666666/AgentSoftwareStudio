package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Agent.ArchitectAgent;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * 负责执行架构设计节点，调用架构师 Agent 生成项目结构蓝图。
 */
@Service
public class ArchitectureNodeService {

    private final ArchitectAgent architectAgent;

    /**
     * 注入架构师 Agent。
     */
    public ArchitectureNodeService(ArchitectAgent architectAgent) {
        this.architectAgent = architectAgent;
    }

    /**
     * 执行架构设计节点，产出项目结构蓝图
     */
    public SoftwareStudioWorkflowData execute(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        logger.accept("2. Architect is designing the project structure.");
        data.structure = architectAgent.designArchitecture(data.prd);
        int fileCount = data.structure == null || data.structure.files() == null ? 0 : data.structure.files().size();
        logger.accept("Architecture ready with " + fileCount + " planned files.");
        return data;
    }
}
