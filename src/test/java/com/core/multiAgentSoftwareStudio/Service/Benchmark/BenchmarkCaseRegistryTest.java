package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkCaseRegistryTest {
    private final BenchmarkCaseRegistry registry = new BenchmarkCaseRegistry();

    /**
     * 新版需求单独标识，旧默认请求仍只运行原六案例。
     */
    @Test
    void keepsBehaviorCasesExplicitAndVersioned() {
        assertEquals(6, registry.select(List.of()).size());
        assertEquals(2, registry.select(List.of("url-shortener-behavior-v1", "poll-system-behavior-v1")).size());
        assertTrue(registry.allCases().stream()
                .filter(c -> BenchmarkBehaviorEvaluator.isBehaviorCase(c.id()))
                .allMatch(c -> c.prompt().contains("HTTP")));
    }
}
