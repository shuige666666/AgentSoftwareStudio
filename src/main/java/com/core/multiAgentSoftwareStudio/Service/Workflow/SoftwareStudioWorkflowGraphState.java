package com.core.multiAgentSoftwareStudio.Service.Workflow;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.Map;

/**
 * 适配 LangGraph4j 的状态包装类，负责从图状态中读取软件工坊工作流数据。
 */
public class SoftwareStudioWorkflowGraphState extends AgentState {
    public static final String DATA_KEY = "workflowData";
    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            DATA_KEY, Channels.base(() -> new SoftwareStudioWorkflowData()));

    /**
     * 使用初始化数据创建工作流图状态对象
     */
    public SoftwareStudioWorkflowGraphState(Map<String, Object> initData) {
        super(initData);
    }

    /**
     * 读取图状态中共享的工作流数据
     */
    public SoftwareStudioWorkflowData workflowData() {
        return this.<SoftwareStudioWorkflowData>value(DATA_KEY).orElseGet(SoftwareStudioWorkflowData::new);
    }
}
