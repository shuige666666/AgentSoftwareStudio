package com.core.multiAgentSoftwareStudio.Config;

import com.core.multiAgentSoftwareStudio.Agent.ArchitectAgent;
import com.core.multiAgentSoftwareStudio.Agent.DeveloperAgent;
import com.core.multiAgentSoftwareStudio.Agent.ProductManagerAgent;
import com.core.multiAgentSoftwareStudio.Agent.DebuggerAgent;
import com.core.multiAgentSoftwareStudio.Agent.TestWriterAgent;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModelName;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.net.InetSocketAddress;
import java.net.Proxy;
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
                        @Value("${spring.ai.openai.base-url}") String baseUrl // 读取你配置文件里的 BaseUrl
        ) {

                return OpenAiChatModel.builder()
                                .baseUrl(baseUrl) // DeepSeek 官方 API 地址
                                .apiKey(apiKey)
                                .modelName("qwen3-coder-flash") // 模型名称
                                .temperature(0.1) // 写代码通常需要严谨，温度设低一点
                                .timeout(Duration.ofMinutes(3)) // 关键！生成代码通常很慢，默认超时可能不够
                                .maxTokens(8192) // 供应商限制最大 8192，避免 invalid_parameter_error
                                .logRequests(true) // 测试阶段开启日志，方便看它发了什么
                                .logResponses(true) // 测试阶段开启日志，方便看它回了什么
                                .build();
        }

        @Bean
        ChatLanguageModel logicModel(@Value("${spring.ai.openai.api-key}") String apiKey,
                        @Value("${spring.ai.openai.base-url}") String baseUrl) {
                return OpenAiChatModel.builder()
                                .baseUrl(baseUrl)
                                .apiKey(apiKey)
                                .modelName("qwen3-max") // 逻辑能力最强，适合 PM 和 架构师
                                .temperature(0.5) // 稍微高一点，增加规划的灵活性
                                .maxTokens(8192)
                                .build();
        }

        /**
         * 创建产品经理 Agent
         */
        @Bean
        ProductManagerAgent productManagerAgent(@Qualifier("logicModel") ChatLanguageModel model) {
                return AiServices.builder(ProductManagerAgent.class)
                                .chatLanguageModel(model)
                                .chatMemory(MessageWindowChatMemory.withMaxMessages(10)) // 简单的短期记忆
                                .build();
        }

        /**
         * 创建架构师 Agent
         */
        @Bean
        ArchitectAgent architectAgent(@Qualifier("logicModel") ChatLanguageModel model) {
                return AiServices.builder(ArchitectAgent.class)
                                .chatLanguageModel(model)
                                .build();
        }

        /**
         * 创建开发工程师 Agent
         */
        @Bean
        DeveloperAgent developerAgent(@Qualifier("coderModel") ChatLanguageModel model) {
                return AiServices.builder(DeveloperAgent.class)
                                .chatLanguageModel(model)
                                .build();
        }

        /**
         * 创建测试用例编写 Agent
         */
        @Bean
        TestWriterAgent testWriterAgent(@Qualifier("coderModel") ChatLanguageModel model) {
                return AiServices.builder(TestWriterAgent.class)
                                .chatLanguageModel(model)
                                .build();
        }

        /**
         * 创建测试/修复工程师 Agent
         */
        @Bean
        DebuggerAgent debuggerAgent(@Qualifier("coderModel") ChatLanguageModel model) {
                return AiServices.builder(DebuggerAgent.class)
                                .chatLanguageModel(model)
                                .build();
        }
}
