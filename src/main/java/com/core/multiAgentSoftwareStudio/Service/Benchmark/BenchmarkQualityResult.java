package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import java.util.List;

public record BenchmarkQualityResult(
        boolean passed,
        List<BenchmarkFinding> findings) {
}
