package com.core.multiAgentSoftwareStudio.Config;

import com.core.multiAgentSoftwareStudio.Agent.ArchitectAgent;
import com.core.multiAgentSoftwareStudio.Agent.DeveloperAgent;
import com.core.multiAgentSoftwareStudio.Agent.ProductManagerAgent;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
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
     * 配置 DeepSeek 模型
     * （同配置文件：OpenAI 协议）
     */
    @Bean
    @Primary // 告诉 Spring：如果有多个 ChatLanguageModel，优先用我这个
    ChatLanguageModel chatLanguageModel(@Value("${spring.ai.openai.api-key}") String apiKey, // 读取你配置文件里的 Key
                                        @Value("${spring.ai.openai.base-url}") String baseUrl // 读取你配置文件里的 BaseUrl
    ) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl) // DeepSeek 官方 API 地址
                .apiKey(apiKey)
                .modelName("deepseek-reasoner") // 模型名称
                .temperature(0.0)           // 写代码通常需要严谨，温度设为 0
                .timeout(Duration.ofMinutes(3)) // 关键！生成代码通常很慢，默认超时可能不够
                .logRequests(true)          // 测试阶段开启日志，方便看它发了什么
                .logResponses(true)         // 测试阶段开启日志，方便看它回了什么
                .build();
    }

    /**
     * 创建产品经理 Agent
     */
    @Bean
    ProductManagerAgent productManagerAgent(ChatLanguageModel chatLanguageModel) {
        return AiServices.builder(ProductManagerAgent.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10)) // 简单的短期记忆
                .build();
    }

    /**
     * 创建架构师 Agent
     */
    @Bean
    ArchitectAgent architectAgent(ChatLanguageModel chatLanguageModel) {
        return AiServices.builder(ArchitectAgent.class)
                .chatLanguageModel(chatLanguageModel)
                .build();
    }

    /**
     * 创建开发工程师 Agent
     */
    @Bean
    DeveloperAgent developerAgent(ChatLanguageModel chatLanguageModel) {
        return AiServices.builder(DeveloperAgent.class)
                .chatLanguageModel(chatLanguageModel)
                .build();
    }
}