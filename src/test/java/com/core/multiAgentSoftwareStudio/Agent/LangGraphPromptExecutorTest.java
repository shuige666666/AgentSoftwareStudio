package com.core.multiAgentSoftwareStudio.Agent;

import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangGraphPromptExecutorTest {

    /**
     * 首次超时只允许再调用一次，并在控制台明确输出重试序号。
     */
    @Test
    void shouldRetryTimeoutOnceAndPrintProgress() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString()))
                .thenThrow(timeoutFailure())
                .thenReturn("recovered");
        LangGraphPromptExecutor executor = new LangGraphPromptExecutor(new LlmUsageMetricsService());
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));

            assertEquals("recovered", executor.execute(model, "prompt"));
        } finally {
            System.setOut(originalOut);
        }

        verify(model, times(2)).generate(anyString());
        String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("[LLM超时重试]"));
        assertTrue(output.contains("timeout retry 1/1"));
        assertTrue(output.contains("第 2 次物理调用"));
    }

    /**
     * 第二次物理调用仍超时时立即结束，不再进入第三次长等待。
     */
    @Test
    void shouldStopAfterTheSingleTimeoutRetry() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenThrow(timeoutFailure());
        LangGraphPromptExecutor executor = new LangGraphPromptExecutor(new LlmUsageMetricsService());

        assertThrows(IllegalStateException.class, () -> executor.execute(model, "prompt"));

        verify(model, times(2)).generate(anyString());
    }

    private IllegalStateException timeoutFailure() {
        return new IllegalStateException("request timed out", new HttpTimeoutException("request timed out"));
    }
}
