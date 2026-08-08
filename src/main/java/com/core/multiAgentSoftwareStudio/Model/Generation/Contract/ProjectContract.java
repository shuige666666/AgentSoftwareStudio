package com.core.multiAgentSoftwareStudio.Model.Generation.Contract;

import com.core.multiAgentSoftwareStudio.Model.Generation.FileBlueprint;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 保存项目内部的接口、视图和前后端交互契约
 */
public record ProjectContract(
        List<ApiEndpointContract> endpoints,
        List<ViewContract> views,
        List<FrontendCallContract> frontendCalls,
        List<FileBlueprint> additionalFiles
) implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 将空列表统一成不可变空集合，方便后续流程安全遍历
     */
    public ProjectContract {
        if (endpoints == null) {
            endpoints = Collections.emptyList();
        }
        if (views == null) {
            views = Collections.emptyList();
        }
        if (frontendCalls == null) {
            frontendCalls = Collections.emptyList();
        }
        if (additionalFiles == null) {
            additionalFiles = Collections.emptyList();
        }
    }
}
