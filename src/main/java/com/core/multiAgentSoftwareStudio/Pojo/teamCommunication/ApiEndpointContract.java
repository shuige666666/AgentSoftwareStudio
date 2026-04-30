package com.core.multiAgentSoftwareStudio.Pojo.teamCommunication;

import java.io.Serializable;

/**
 * 描述后端接口的结构化契约
 */
public record ApiEndpointContract(
        String method,
        String path,
        String requestDto,
        String responseDto,
        String implementedBy,
        String description
) implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 规范化接口契约中的空字段，避免后续校验时反复判空
     */
    public ApiEndpointContract {
        method = method == null || method.isBlank() ? "GET" : method.trim().toUpperCase();
        path = path == null || path.isBlank() ? "/" : path.trim();
        requestDto = requestDto == null ? "" : requestDto.trim();
        responseDto = responseDto == null ? "" : responseDto.trim();
        implementedBy = implementedBy == null ? "" : implementedBy.trim();
        description = description == null ? "" : description.trim();
    }
}
