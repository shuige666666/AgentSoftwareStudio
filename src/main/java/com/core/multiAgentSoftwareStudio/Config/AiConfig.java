package com.core.multiAgentSoftwareStudio.Config;

import com.core.multiAgentSoftwareStudio.Agent.ArchitectAgent;
import com.core.multiAgentSoftwareStudio.Agent.ContractAgent;
import com.core.multiAgentSoftwareStudio.Agent.DeveloperAgent;
import com.core.multiAgentSoftwareStudio.Agent.FrontendReviewAgent;
import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.core.multiAgentSoftwareStudio.Service.Metric.ObservedDeepSeekChatModel;
import com.core.multiAgentSoftwareStudio.Agent.ProductManagerAgent;
import com.core.multiAgentSoftwareStudio.Agent.DebuggerAgent;
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

        /**
         * 模型配置
         * （同配置文件：OpenAI 协议）
         */
        @Bean
        @Primary // 告诉 Spring：如果有多个 ChatLanguageModel，优先用我这个
        ChatLanguageModel coderModel(@Value("${spring.ai.openai.api-key}") String apiKey, // 读取你配置文件里的 Key
                        @Value("${spring.ai.openai.base-url}") String baseUrl, // 读取你配置文件里的 BaseUrl
                        ObjectMapper objectMapper,
                        LlmUsageMetricsService metricsService
        ) {

                return new ObservedDeepSeekChatModel(
                                apiKey,
                                baseUrl,
                                "deepseek-v4-flash",
                                0.1,
                                8192,
                                Duration.ofMinutes(8),
                                objectMapper,
                                metricsService);
        }

        @Bean
        ChatLanguageModel logicModel(@Value("${spring.ai.openai.api-key}") String apiKey,
                        @Value("${spring.ai.openai.base-url}") String baseUrl,
                        ObjectMapper objectMapper,
                        LlmUsageMetricsService metricsService) {
                return new ObservedDeepSeekChatModel(
                                apiKey,
                                baseUrl,
                                "deepseek-v4-pro",
                                0.5,
                                8192,
                                Duration.ofMinutes(5),
                                objectMapper,
                                metricsService);
        }

        /**
         * 创建产品经理 Agent
         */
        @Bean
        ProductManagerAgent productManagerAgent(@Qualifier("logicModel") ChatLanguageModel model,
                        LangGraphPromptExecutor promptExecutor,
                        ObjectMapper objectMapper) {
                return new ProductManagerAgent(model, promptExecutor, objectMapper);
        }

        /**
         * 创建架构师 Agent
         */
        @Bean
        ArchitectAgent architectAgent(@Qualifier("logicModel") ChatLanguageModel model,
                        LangGraphPromptExecutor promptExecutor,
                        ObjectMapper objectMapper) {
                return new ArchitectAgent(model, promptExecutor, objectMapper);
        }

        /**
         * 创建开发工程师 Agent
         */
        /**
         * 创建接口契约 Agent
         */
        @Bean
        ContractAgent contractAgent(@Qualifier("logicModel") ChatLanguageModel model,
                        LangGraphPromptExecutor promptExecutor,
                        ObjectMapper objectMapper) {
                return new ContractAgent(model, promptExecutor, objectMapper);
        }

        @Bean
        DeveloperAgent developerAgent(@Qualifier("coderModel") ChatLanguageModel model,
                        LangGraphPromptExecutor promptExecutor,
                        ObjectMapper objectMapper) {
                return new DeveloperAgent(model, promptExecutor, objectMapper);
        }

        /**
         * 创建测试用例编写 Agent
         */
        @Bean
        TestWriterAgent testWriterAgent(@Qualifier("coderModel") ChatLanguageModel model,
                        LangGraphPromptExecutor promptExecutor,
                        ObjectMapper objectMapper) {
                return new TestWriterAgent(model, promptExecutor, objectMapper);
        }

        /**
         * 创建前端审查修复 Agent
         */
        @Bean
        FrontendReviewAgent frontendReviewAgent(@Qualifier("coderModel") ChatLanguageModel model,
                        LangGraphPromptExecutor promptExecutor,
                        ObjectMapper objectMapper) {
                return new FrontendReviewAgent(model, promptExecutor, objectMapper);
        }

        /**
         * 创建测试/修复工程师 Agent
         */
        @Bean
        DebuggerAgent debuggerAgent(@Qualifier("coderModel") ChatLanguageModel model,
                        LangGraphPromptExecutor promptExecutor,
                        ObjectMapper objectMapper) {
                return new DebuggerAgent(model, promptExecutor, objectMapper);
        }
}
