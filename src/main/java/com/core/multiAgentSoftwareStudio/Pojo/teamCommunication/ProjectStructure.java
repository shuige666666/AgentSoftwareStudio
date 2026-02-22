package com.core.multiAgentSoftwareStudio.Pojo.teamCommunication;



import java.util.List;

// 2. 项目结构 (架构师的产出)
public record ProjectStructure(
        String rootPackage,
        String projectType,    // 新增：例如 "PURE_JAVA_MAVEN" 或 "SPRING_BOOT"
        String mainClassName,  // 新增：例如 "com.example.game.SnakeGame"
        List<FileBlueprint> files
) {}