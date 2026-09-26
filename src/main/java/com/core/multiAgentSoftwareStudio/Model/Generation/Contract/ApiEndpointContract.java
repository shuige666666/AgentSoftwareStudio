package com.core.multiAgentSoftwareStudio.Model.Generation.Contract;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 描述后端接口的结构化契约
 */
public record ApiEndpointContract(
        String method,
        String path,
        String requestDto,
        String responseDto,
        String implementedBy,
        String description,
        Map<String, String> requestFields,
        Map<String, String> responseFields,
        Map<String, String> errorResponses,
        List<String> behaviorIds
) implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 规范化接口契约中的空字段，避免后续校验时反复判空
     */
    public ApiEndpointContract {
        // 缺失的公开接口不能静默变成 GET /，交由规划字段校验定向反馈。
        method = method == null ? "" : method.trim().toUpperCase();
        path = path == null ? "" : path.trim();
        requestDto = requestDto == null ? "" : requestDto.trim();
        responseDto = responseDto == null ? "" : responseDto.trim();
        implementedBy = implementedBy == null ? "" : implementedBy.trim();
        description = description == null ? "" : description.trim();
        requestFields = requestFields == null ? Map.of() : Map.copyOf(requestFields);
        responseFields = responseFields == null ? Map.of() : Map.copyOf(responseFields);
        errorResponses = errorResponses == null ? Map.of() : Map.copyOf(errorResponses);
        behaviorIds = behaviorIds == null ? List.of() : List.copyOf(behaviorIds);
    }

    public ApiEndpointContract(String method, String path, String requestDto, String responseDto,
                               String implementedBy, String description) {
        this(method, path, requestDto, responseDto, implementedBy, description,
                Map.of(), Map.of(), Map.of(), List.of());
    }
}
