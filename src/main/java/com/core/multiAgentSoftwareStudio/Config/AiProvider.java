package com.core.multiAgentSoftwareStudio.Config;

import java.util.Locale;

/**
 * 标识当前实际调用的 API 提供方，用于选择供应商专用的观测行为。
 */
public enum AiProvider {
    DEEPSEEK_DIRECT(true),
    ALIBABA_BAILIAN(false),
    GENERIC_OPENAI(false);

    private final boolean deepSeekCacheMetricsEnabled;

    AiProvider(boolean deepSeekCacheMetricsEnabled) {
        this.deepSeekCacheMetricsEnabled = deepSeekCacheMetricsEnabled;
    }

    public boolean deepSeekCacheMetricsEnabled() {
        return deepSeekCacheMetricsEnabled;
    }

    /**
     * 将 YAML 中适合阅读的短横线名称转换为枚举，并对未知配置立即报错。
     */
    public static AiProvider fromConfig(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "deepseek-direct", "deepseek_direct" -> DEEPSEEK_DIRECT;
            case "alibaba-bailian", "alibaba_bailian" -> ALIBABA_BAILIAN;
            case "generic-openai", "generic_openai" -> GENERIC_OPENAI;
            default -> throw new IllegalArgumentException("Unsupported studio.ai.provider: " + value);
        };
    }
}
