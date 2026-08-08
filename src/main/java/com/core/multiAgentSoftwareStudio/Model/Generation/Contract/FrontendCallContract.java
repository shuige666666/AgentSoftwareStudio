package com.core.multiAgentSoftwareStudio.Model.Generation.Contract;

import java.io.Serializable;

/**
 * 描述前端文件调用后端接口的契约
 */
public record FrontendCallContract(
        String sourceFile,
        String method,
        String path,
        String description
) implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 规范化前端调用契约，避免大小写和路径分隔符导致误判
     */
    public FrontendCallContract {
        sourceFile = sourceFile == null ? "" : sourceFile.trim().replace("\\", "/");
        method = method == null || method.isBlank() ? "GET" : method.trim().toUpperCase();
        path = path == null || path.isBlank() ? "/" : path.trim();
        description = description == null ? "" : description.trim();
    }
}
