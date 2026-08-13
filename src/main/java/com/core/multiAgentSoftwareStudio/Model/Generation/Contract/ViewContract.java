package com.core.multiAgentSoftwareStudio.Model.Generation.Contract;

import java.io.Serializable;

/**
 * 描述 MVC 视图模板和 Controller 返回值的契约
 */
public record ViewContract(
        String name,
        String templatePath,
        String returnedBy,
        String description
) implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 规范化视图契约字段，保证模板路径和视图名都有稳定兜底值
     */
    public ViewContract {
        name = name == null ? "" : name.trim();
        templatePath = templatePath == null || templatePath.isBlank()
                ? defaultTemplatePath(name)
                : templatePath.trim().replace("\\", "/");
        returnedBy = returnedBy == null ? "" : returnedBy.trim();
        description = description == null ? "" : description.trim();
    }

    /**
     * 根据视图名推导 Thymeleaf 默认模板路径
     */
    private static String defaultTemplatePath(String viewName) {
        if (viewName == null || viewName.isBlank()) {
            return "src/main/resources/templates/index.html";
        }
        return "src/main/resources/templates/" + viewName.trim() + ".html";
    }
}
