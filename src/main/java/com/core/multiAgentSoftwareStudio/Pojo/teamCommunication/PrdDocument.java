package com.core.multiAgentSoftwareStudio.Pojo.teamCommunication;

import java.util.List;

// 1. 产品文档 (PM 的产出)
    public record PrdDocument(
        String projectName,
        String projectGoal,             // 项目目标
        List<String> userStories,       // 用户故事/功能点
        List<String> techStack          // 建议的技术栈 (e.g., Spring Boot, H2)
    ) {}