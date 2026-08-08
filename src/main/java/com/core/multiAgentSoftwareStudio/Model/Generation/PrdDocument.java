package com.core.multiAgentSoftwareStudio.Model.Generation;

import com.core.multiAgentSoftwareStudio.Config.Jackson.StringOrObjectListDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.Serializable;
import java.util.List;

// 1. 产品文档 (PM 的产出)
    public record PrdDocument(
        String projectName,
        String projectGoal,             // 项目目标
        @JsonDeserialize(using = StringOrObjectListDeserializer.class)
        List<String> userStories,       // 用户故事/功能点
        List<String> techStack          // 建议的技术栈 (e.g., Spring Boot, H2)
    ) implements Serializable {
        private static final long serialVersionUID = 1L;
    }
