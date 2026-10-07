package com.core.multiAgentSoftwareStudio.Service.Metric;

import com.core.multiAgentSoftwareStudio.Agent.LangGraphPromptExecutor;
import com.core.multiAgentSoftwareStudio.Config.AiConfig;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证工具调用开关从 Spring 配置传到两个模型的请求体，不发送模型请求。 */
class ToolCallingConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AiConfig.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(LlmUsageMetricsService.class)
            .withBean(LangGraphPromptExecutor.class)
            .withPropertyValues(
                    "spring.ai.openai.api-key=test-key",
                    "spring.ai.openai.base-url=http://localhost",
                    "studio.ai.provider=alibaba-bailian");

    /** 未提供新配置时保持原行为，避免旧部署因升级自动开启多工具响应。 */
    @Test
    void defaultsToSingleToolResponsesWhenPropertyIsAbsent() {
        assertParallelToolCalls(contextRunner, false);
    }

    /** 显式配置 true 或 false 时，两种模型的工具请求都必须使用该值。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void appliesConfiguredValueToBothModelRequests(boolean enabled) {
        assertParallelToolCalls(contextRunner.withPropertyValues(
                "studio.ai.parallel-tool-calls=" + enabled), enabled);
    }

    /** 检查真实 Spring 创建的模型请求，并确认普通规划请求不携带工具开关。 */
    private void assertParallelToolCalls(ApplicationContextRunner runner, boolean enabled) {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            for (String beanName : List.of(AiConfig.CODER_MODEL_BEAN_NAME, AiConfig.LOGIC_MODEL_BEAN_NAME)) {
                var model = context.getBean(beanName, ObservedOpenAiCompatibleChatModel.class);
                var request = model.buildToolRequest(List.of(ToolChatMessage.user("inspect")), List.of());

                assertTrue(request.path("parallel_tool_calls").isBoolean(), beanName);
                assertEquals(enabled, request.path("parallel_tool_calls").asBoolean(), beanName);
                assertFalse(model.buildRequest(List.of(UserMessage.from("plan")))
                        .has("parallel_tool_calls"), beanName);
            }
        });
    }
}
