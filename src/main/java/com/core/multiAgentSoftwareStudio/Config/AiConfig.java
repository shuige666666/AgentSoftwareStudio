package com.core.multiAgentSoftwareStudio.Config;

import com.core.multiAgentSoftwareStudio.Agent.ArchitectAgent;
import com.core.multiAgentSoftwareStudio.Agent.ContractAgent;
import com.core.multiAgentSoftwareStudio.Agent.ContractRepairAgent;
import com.core.multiAgentSoftwareStudio.Agent.DeveloperAgent;
import com.core.multiAgentSoftwareStudio.Agent.FrontendReviewAgent;
import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.core.multiAgentSoftwareStudio.Service.Metric.ObservedOpenAiCompatibleChatModel;
import com.core.multiAgentSoftwareStudio.Agent.ProductManagerAgent;
import com.core.multiAgentSoftwareStudio.Agent.DebuggerAgent;
import com.core.multiAgentSoftwareStudio.Agent.ImplementationRepairAgent;
import com.core.multiAgentSoftwareStudio.Agent.TestWriterAgent;
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
        public static final String CODER_MODEL_NAME = "qwen3.7-flash-2026-07-15";
        public static final double CODER_MODEL_TEMPERATURE = 0.1;
        public static final int CODER_MODEL_MAX_OUTPUT_TOKENS = 8192;
        public static final Duration CODER_MODEL_TIMEOUT = Duration.ofMinutes(4);
        public static final boolean CODER_MODEL_JSON_OUTPUT_ENABLED = true;

        public static final String LOGIC_MODEL_BEAN_NAME = "logicModel";
        public static final String LOGIC_MODEL_NAME = "qwen3.7-plus-2026-05-26";
        public static final double LOGIC_MODEL_TEMPERATURE = 0.5;
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
        ChatLanguageModel coderModel(@Value("${spring.ai.openai.api-key}") String apiKey, // 读取你配置文件里的 Key
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
                                aiProvider.deepSeekCacheMetricsEnabled(),
                                CODER_MODEL_JSON_OUTPUT_ENABLED);
        }

        @Bean(name = LOGIC_MODEL_BEAN_NAME)
        ChatLanguageModel logicModel(@Value("${spring.ai.openai.api-key}") String apiKey,
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
                                aiProvider.deepSeekCacheMetricsEnabled(),
                                LOGIC_MODEL_JSON_OUTPUT_ENABLED);
        }

        /**
         * 创建产品经理 Agent
         */
        @Bean
        ProductManagerAgent productManagerAgent(@Qualifier(LOGIC_MODEL_BEAN_NAME) ChatLanguageModel model,
                        LangGraphPromptExecutor promptExecutor,
                        ObjectMapper objectMapper) {
                return new ProductManagerAgent(model, promptExecutor, objectMapper);
        }

        /**
         * 创建架构师 Agent
         */
        @Bean
        ArchitectAgent architectAgent(@Qualifier(LOGIC_MODEL_BEAN_NAME) ChatLanguageModel model,
                        LangGraphPromptExecutor promptExecutor,
                        ObjectMapper objectMapper) {
                return new ArchitectAgent(model, promptExecutor, objectMapper);
        }


        /**
         * 创建接口契约 Agent
         */
        @Bean
        ContractAgent contractAgent(@Qualifier(LOGIC_MODEL_BEAN_NAME) ChatLanguageModel model,
                        LangGraphPromptExecutor promptExecutor,
                        ObjectMapper objectMapper) {
                return new ContractAgent(model, promptExecutor, objectMapper);
        }

        /**
         * 创建开发工程师 Agent
         */
        @Bean
        DeveloperAgent developerAgent(@Qualifier(CODER_MODEL_BEAN_NAME) ChatLanguageModel model,
                        LangGraphPromptExecutor promptExecutor,
                        ObjectMapper objectMapper) {
                return new DeveloperAgent(model, promptExecutor, objectMapper);
        }

        /**
         * 创建测试用例编写 Agent
         */
        @Bean
        TestWriterAgent testWriterAgent(@Qualifier(CODER_MODEL_BEAN_NAME) ChatLanguageModel model,
                        LangGraphPromptExecutor promptExecutor,
                        ObjectMapper objectMapper) {
                return new TestWriterAgent(model, promptExecutor, objectMapper);
        }

        /**
         * 创建前端审查修复 Agent
         */
        @Bean
        FrontendReviewAgent frontendReviewAgent(@Qualifier(CODER_MODEL_BEAN_NAME) ChatLanguageModel model,
                        LangGraphPromptExecutor promptExecutor,
                        ObjectMapper objectMapper) {
                return new FrontendReviewAgent(model, promptExecutor, objectMapper);
        }

        /**
         * 创建测试/修复工程师 Agent
         */
        @Bean
        DebuggerAgent debuggerAgent(@Qualifier(CODER_MODEL_BEAN_NAME) ChatLanguageModel model,
                        LangGraphPromptExecutor promptExecutor,
                        ObjectMapper objectMapper) {
                return new DebuggerAgent(model, promptExecutor, objectMapper);
        }

        /**
         * 创建真实编译反馈驱动的多文件实现修复 Agent。
         */
        @Bean
        ImplementationRepairAgent implementationRepairAgent(
                        @Qualifier(CODER_MODEL_BEAN_NAME) ChatLanguageModel model,
                        LangGraphPromptExecutor promptExecutor,
                        ObjectMapper objectMapper) {
                return new ImplementationRepairAgent(model, promptExecutor, objectMapper);
        }

        /**
         * 创建测试绿色后负责行为契约闭环的专项修复 Agent。
         */
        @Bean
        ContractRepairAgent contractRepairAgent(
                        @Qualifier(CODER_MODEL_BEAN_NAME) ChatLanguageModel model,
                        LangGraphPromptExecutor promptExecutor,
                        ObjectMapper objectMapper) {
                return new ContractRepairAgent(model, promptExecutor, objectMapper);
        }
}
