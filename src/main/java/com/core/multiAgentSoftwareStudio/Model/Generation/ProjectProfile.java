package com.core.multiAgentSoftwareStudio.Model.Generation;

import java.io.Serializable;
import java.util.Set;

/**
 * 固化生成项目的运行时、框架和测试工具版本，避免模型自行组合不兼容的工程骨架。
 */
public record ProjectProfile(
        String id,
        String projectType,
        int javaVersion,
        String springBootVersion,
        boolean requireSpringContextTest,
        Set<String> forbiddenDependencies) implements Serializable {

    private static final long serialVersionUID = 1L;

    public ProjectProfile {
        forbiddenDependencies = forbiddenDependencies == null ? Set.of() : Set.copyOf(forbiddenDependencies);
    }

    public static ProjectProfile java17SpringBoot() {
        return new ProjectProfile(
                "java17-spring-boot-3.2",
                "SPRING_BOOT",
                17,
                "3.2.4",
                true,
                Set.of("org.springframework:spring-websocket-test"));
    }

    public static ProjectProfile generic(String projectType) {
        return new ProjectProfile(
                "generic", projectType == null ? "UNKNOWN" : projectType, 17, null, false, Set.of());
    }
}
