package com.core.multiAgentSoftwareStudio.Config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiProviderTest {

    @Test
    void enablesDedicatedCacheMetricsOnlyForDirectDeepSeekApi() {
        assertTrue(AiProvider.fromConfig("deepseek-direct").deepSeekCacheMetricsEnabled());
        assertFalse(AiProvider.fromConfig("alibaba-bailian").deepSeekCacheMetricsEnabled());
        assertFalse(AiProvider.fromConfig("generic-openai").deepSeekCacheMetricsEnabled());
    }

    @Test
    void rejectsUnknownProviderInsteadOfSilentlyChoosingWrongMetrics() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> AiProvider.fromConfig("unknown-provider"));

        assertEquals("Unsupported studio.ai.provider: unknown-provider", error.getMessage());
    }
}
