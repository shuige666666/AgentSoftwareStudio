package com.core.multiAgentSoftwareStudio.Pojo.teamCommunication;

import java.util.List;

// 3. 单个文件蓝图 (架构师发给开发者的指令)
    public record FileBlueprint(
        String fileName,                // e.g., "GameController.java"
        String filePath,                // e.g., "src/main/java/com/..."
        String functionalityDescription,// 这个文件具体的职责描述
        List<String> keyMethods         // 建议包含的关键方法名
    ) {}