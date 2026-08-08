package com.core.multiAgentSoftwareStudio.Model.Benchmark;

import java.util.List;

public record BenchmarkQualityResult(
        boolean passed,
        List<BenchmarkFinding> findings) {
}
