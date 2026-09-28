package com.core.multiAgentSoftwareStudio.Config;

import com.core.multiAgentSoftwareStudio.Agent.PlanningAgent;
import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.core.multiAgentSoftwareStudio.Service.Metric.ObservedOpenAiCompatibleChatModel;
import com.core.multiAgentSoftwareStudio.Agent.LangGraphPromptExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * 用于注册 AI 额外功能所需的配置组件
 */
@Configuration
public class AiConfig {

        // 基准报告与模型实例共用这组常量，防止记录参数与真实调用参数偏离。
        public static final String CODER_MODEL_BEAN_NAME = "coderModel";
        public static final String CODER_MODEL_NAME = "qwen3.7-flash";
        public static final double CODER_MODEL_TEMPERATURE = 0.1;
        public static final int CODER_MODEL_MAX_OUTPUT_TOKENS = 8192;
        public static final Duration CODER_MODEL_TIMEOUT = Duration.ofMinutes(4);
        public static final boolean CODER_MODEL_JSON_OUTPUT_ENABLED = true;

        public static final String LOGIC_MODEL_BEAN_NAME = "logicModel";
        public static final String LOGIC_MODEL_NAME = "qwen3.8-max-0902";
        public static final double LOGIC_MODEL_TEMPERATURE = 0.4;
        public static final int LOGIC_MODEL_MAX_OUTPUT_TOKENS = 8192;
        public static final Duration LOGIC_MODEL_TIMEOUT = Duration.ofMinutes(8);
        public static final boolean LOGIC_MODEL_JSON_OUTPUT_ENABLED = true;

        /**
         * 将显式的 API 提供方配置转换为类型安全枚举，避免根据模型名或 URL 误判。
         */
        @Bean
        AiProvider aiProvider(@Value("${studio.ai.provider:deepseek-direct}") String provider) {
                return AiProvider.fromConfig(provider);
        }

        /**
         * 模型配置
         * （同配置文件：OpenAI 协议）
         */
        @Bean(name = CODER_MODEL_BEAN_NAME)
        @Primary // 告诉 Spring：如果有多个 ChatLanguageModel，优先用我这个
        ObservedOpenAiCompatibleChatModel coderModel(@Value("${spring.ai.openai.api-key}") String apiKey, // 读取你配置文件里的 Key
                        @Value("${spring.ai.openai.base-url}") String baseUrl, // 读取你配置文件里的 BaseUrl
                        ObjectMapper objectMapper,
                        LlmUsageMetricsService metricsService,
                        AiProvider aiProvider
        ) {

                return new ObservedOpenAiCompatibleChatModel(
                                apiKey,
                                baseUrl,
                                CODER_MODEL_NAME,
                                CODER_MODEL_TEMPERATURE,
                                CODER_MODEL_MAX_OUTPUT_TOKENS,
                                CODER_MODEL_TIMEOUT,
                                objectMapper,
                                metricsService,
                                aiProvider,
                                CODER_MODEL_JSON_OUTPUT_ENABLED);
        }

        @Bean(name = LOGIC_MODEL_BEAN_NAME)
        ObservedOpenAiCompatibleChatModel logicModel(@Value("${spring.ai.openai.api-key}") String apiKey,
                        @Value("${spring.ai.openai.base-url}") String baseUrl,
                        ObjectMapper objectMapper,
                        LlmUsageMetricsService metricsService,
                        AiProvider aiProvider) {
                return new ObservedOpenAiCompatibleChatModel(
                                apiKey,
                                baseUrl,
                                LOGIC_MODEL_NAME,
                                LOGIC_MODEL_TEMPERATURE,
                                LOGIC_MODEL_MAX_OUTPUT_TOKENS,
                                LOGIC_MODEL_TIMEOUT,
                                objectMapper,
                                metricsService,
                                aiProvider,
                                LOGIC_MODEL_JSON_OUTPUT_ENABLED);
        }

        /**
         * 将同一个逻辑模型用于需求和设计两步，旧三角色退出主链。
         */
        @Bean
        PlanningAgent planningAgent(@Qualifier(LOGIC_MODEL_BEAN_NAME) ChatLanguageModel model,
                        LangGraphPromptExecutor promptExecutor,
                        ObjectMapper objectMapper) {
                return new PlanningAgent(model, promptExecutor, objectMapper);
        }
}
