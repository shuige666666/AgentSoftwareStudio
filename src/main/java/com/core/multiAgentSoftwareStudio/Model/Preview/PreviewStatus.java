package com.core.multiAgentSoftwareStudio.Model.Preview;

/**
 * 描述生成项目预览容器的当前状态和访问入口。
 */
public record PreviewStatus(
        String projectId,
        String state,
        String url,
        String message) {
}
