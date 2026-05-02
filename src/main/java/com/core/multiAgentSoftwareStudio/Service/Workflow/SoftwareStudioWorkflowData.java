package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.GenerationBatch;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.GenerationPlan;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.PrdDocument;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.ProjectContract;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.SourceCode;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 保存软件工坊 LangGraph 工作流中所有节点共享的可序列化状态。
 */
public class SoftwareStudioWorkflowData implements Serializable {
    private static final long serialVersionUID = 1L;

    // 这个内部状态类就是整张 LangGraph 图共享的“真状态”。
    // 你后面如果要加新节点，通常也是在这里补字段，然后让节点读写它。
    public String userRequest = "";
    public int maxRetries;
    public List<SourceCode> codes = new ArrayList<>();
    public List<String> validationWarnings = new ArrayList<>();
    public PrdDocument prd;
    public ProjectStructure structure;
    public ProjectContract contract;
    public GenerationPlan generationPlan = new GenerationPlan(List.of());
    public int currentBatchIndex;
    // 这里放进 LangGraph state 的对象都会被序列化，路径统一存字符串更稳妥。
    public String projectPath;
    public String executionResult;
    public String testResult;
    public String pendingFixLog;
    public String pendingErrorType;
    public boolean success;
    public boolean shouldFix;
    public int currentAttempt = 1;

    /**
     * 创建默认的空工作流状态
     */
    public SoftwareStudioWorkflowData() {
    }

    /**
     * 使用用户请求和最大重试次数初始化工作流状态
     */
    public SoftwareStudioWorkflowData(String userRequest, int maxRetries) {
        this.userRequest = userRequest;
        this.maxRetries = maxRetries;
    }

    /**
     * 判断是否还有未处理的代码生成批次
     */
    public boolean hasMoreBatches() {
        // 是否还有下一批待生成文件，供条件边决定要不要继续回到 generate_batch。
        return generationPlan != null
                && generationPlan.batches() != null
                && currentBatchIndex < generationPlan.batches().size();
    }

    /**
     * 获取当前游标所指向的生成批次
     */
    public GenerationBatch currentBatch() {
        // 读取当前批次时不额外推进索引，索引推进统一放在 validate_batch 后面。
        if (!hasMoreBatches()) {
            return null;
        }
        return generationPlan.batches().get(currentBatchIndex);
    }
}
