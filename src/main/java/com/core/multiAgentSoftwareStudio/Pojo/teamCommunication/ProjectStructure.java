package com.core.multiAgentSoftwareStudio.Pojo.teamCommunication;



import java.util.List;

// 2. 项目结构 (架构师的产出)
    public record ProjectStructure(
        String rootPackage,             // e.g., "com.example.snake"
        List<FileBlueprint> files       // 需要创建的文件列表
    ) {}