package com.core.multiAgentSoftwareStudio.Config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiProviderTest {

    @Test
    void resolvesProviderForDedicatedUsageParsing() {
        assertEquals(AiProvider.DEEPSEEK_DIRECT, AiProvider.fromConfig("deepseek-direct"));
        assertEquals(AiProvider.ALIBABA_BAILIAN, AiProvider.fromConfig("alibaba-bailian"));
        assertEquals(AiProvider.GENERIC_OPENAI, AiProvider.fromConfig("generic-openai"));
    }

    @Test
    void rejectsUnknownProviderInsteadOfSilentlyChoosingWrongMetrics() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> AiProvider.fromConfig("unknown-provider"));

        assertEquals("Unsupported studio.ai.provider: unknown-provider", error.getMessage());
    }
}
