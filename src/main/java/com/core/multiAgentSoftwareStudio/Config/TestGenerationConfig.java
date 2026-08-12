package com.core.multiAgentSoftwareStudio.Config;

/**
 * 集中维护垂直切片首次测试生成和测试修复的规模控制参数。
 */
public final class TestGenerationConfig {

    /**
     * 首次切片验收测试的建议最小方法数；验收标准较少时允许低于此值，不为凑数量制造重复测试。
     */
    public static final int RECOMMENDED_MIN_TEST_METHODS_PER_SLICE = 6;

    /**
     * 首次切片验收测试的建议最大方法数；这是 Prompt 软上限，避免为截断数量破坏完整测试类。
     */
    public static final int RECOMMENDED_MAX_TEST_METHODS_PER_SLICE = 15;

    /**
     * 每条验收标准除主路径外最多补充的高风险边界测试数，限制同一行为被穷举式重复覆盖。
     */
    public static final int MAX_RISK_BOUNDARY_TESTS_PER_CRITERION = 2;

    private TestGenerationConfig() {
    }
}
