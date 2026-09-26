package com.core.multiAgentSoftwareStudio.Model.Generation;

import com.core.multiAgentSoftwareStudio.Config.Jackson.StringOrObjectListDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.Serializable;
import java.util.List;

/**
 * 规划步骤整理的需求摘要，保留必需行为与实现约束。
 */
public record PrdDocument(
        String projectName,
        String projectGoal,             // 项目目标
        @JsonDeserialize(using = StringOrObjectListDeserializer.class)
        List<String> userStories,       // 用户故事/功能点
        List<String> techStack,         // 明确的技术限制与技术建议
        List<String> businessRules,
        List<String> assumptions,
        List<String> openQuestions
    ) implements Serializable {
        private static final long serialVersionUID = 1L;

        public PrdDocument {
            userStories = userStories == null ? List.of() : List.copyOf(userStories);
            techStack = techStack == null ? List.of() : List.copyOf(techStack);
            businessRules = businessRules == null ? List.of() : List.copyOf(businessRules);
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
            openQuestions = openQuestions == null ? List.of() : List.copyOf(openQuestions);
        }

        public PrdDocument(String projectName, String projectGoal, List<String> userStories,
                           List<String> techStack) {
            this(projectName, projectGoal, userStories, techStack, List.of(), List.of(), List.of());
        }
    }
