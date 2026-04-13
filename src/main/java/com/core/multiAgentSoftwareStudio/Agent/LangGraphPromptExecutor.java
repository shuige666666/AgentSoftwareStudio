package com.core.multiAgentSoftwareStudio.Agent;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 使用 LangGraph4j 执行一次单轮 LLM 调用：
 * START -> llm -> END
 */
@Component
public class LangGraphPromptExecutor {

    public String execute(ChatLanguageModel model, String prompt) {
        // 单节点动作：读取当前 state 中最后一条消息作为本轮输入，并把模型回复追加回 state。
        NodeAction<SingleTurnState> llmNode = state -> {
            List<String> messages = state.messages();
            String userPrompt = messages.isEmpty() ? prompt : messages.get(messages.size() - 1);
            String answer = model.generate(userPrompt);
            return Map.of(SingleTurnState.MESSAGES_KEY, answer);
        };

        try {
            // 构建最小单轮图：START -> llm -> END
            var graph = new StateGraph<>(SingleTurnState.SCHEMA, SingleTurnState::new)
                    .addNode("llm", node_async(llmNode))
                    .addEdge(START, "llm")
                    .addEdge("llm", END)
                    .compile();

            SingleTurnState lastState = null;
            // stream 会产出每一步执行后的状态；这里取最后一个状态作为最终结果。
            for (var output : graph.stream(Map.of(SingleTurnState.MESSAGES_KEY, prompt))) {
                lastState = output.state();
            }

            // 兜底：若图执行后没有可用消息，直接调用模型避免返回空值。
            if (lastState == null || lastState.messages().isEmpty()) {
                return model.generate(prompt);
            }
            return lastState.messages().get(lastState.messages().size() - 1);
        } catch (GraphStateException e) {
            throw new IllegalStateException("LangGraph4j graph compile failed", e);
        } catch (Exception e) {
            throw new IllegalStateException("LangGraph4j execution failed", e);
        }
    }

    static class SingleTurnState extends AgentState {
        static final String MESSAGES_KEY = "messages";
        // appender channel: 每次节点返回的消息都会追加到同一个列表中。
        static final Map<String, Channel<?>> SCHEMA = Map.of(
                MESSAGES_KEY, Channels.appender(ArrayList::new));

        public SingleTurnState(Map<String, Object> initData) {
            super(initData);
        }

        public List<String> messages() {
            return this.<List<String>>value(MESSAGES_KEY).orElse(List.of());
        }
    }
}
