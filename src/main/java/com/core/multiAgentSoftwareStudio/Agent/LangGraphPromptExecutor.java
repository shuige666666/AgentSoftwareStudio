package com.core.multiAgentSoftwareStudio.Agent;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.springframework.stereotype.Component;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
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
    private static final int MAX_TIMEOUT_RETRIES = 2;
    private static final long RETRY_BACKOFF_MILLIS = 1500L;

    public String execute(ChatLanguageModel model, String prompt) {
        // 单节点动作：读取当前 state 中最后一条消息作为本轮输入，并把模型回复追加回 state。
        NodeAction<SingleTurnState> llmNode = state -> {
            List<String> messages = state.messages();
            String userPrompt = messages.isEmpty() ? prompt : messages.get(messages.size() - 1);
            String answer = generateWithRetry(model, userPrompt);
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
                return generateWithRetry(model, prompt);
            }
            return lastState.messages().get(lastState.messages().size() - 1);
        } catch (GraphStateException e) {
            throw new IllegalStateException("LangGraph4j graph compile failed", e);
        } catch (Exception e) {
            throw new IllegalStateException("LangGraph4j execution failed", e);
        }
    }

    /**
     * 兼容层模型调用偶发会因为网络抖动或供应商排队触发 timeout。
     * 这里仅对超时类异常做少量重试，避免一次瞬时波动就中断整条工作流。
     */
    private String generateWithRetry(ChatLanguageModel model, String prompt) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= MAX_TIMEOUT_RETRIES + 1; attempt++) {
            try {
                return model.generate(prompt);
            } catch (RuntimeException e) {
                lastException = e;
                if (!isTimeoutError(e) || attempt > MAX_TIMEOUT_RETRIES) {
                    throw e;
                }
                sleepQuietly(RETRY_BACKOFF_MILLIS * attempt);
            }
        }
        throw lastException == null ? new IllegalStateException("Unknown LLM invocation failure") : lastException;
    }

    private boolean isTimeoutError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof InterruptedIOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry the LLM call", e);
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
