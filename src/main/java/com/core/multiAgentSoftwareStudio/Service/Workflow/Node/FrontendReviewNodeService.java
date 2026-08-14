package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentMode;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentRuntime;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.function.Consumer;

/**
 * 使用真实文件和编译反馈检查前后端接口、模板与浏览器脚本的一致性。
 */
@Service
public class FrontendReviewNodeService {

    private final WorkspaceAgentRuntime workspaceAgentRuntime;

    public FrontendReviewNodeService(WorkspaceAgentRuntime workspaceAgentRuntime) {
        this.workspaceAgentRuntime = workspaceAgentRuntime;
    }

    /**
     * 仅在项目包含前端资源时启动工具会话，允许同步修正前端文件和对应后端接口。
     */
    public SoftwareStudioWorkflowData execute(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        if (!hasFrontend(data)) {
            logger.accept("5. No frontend files detected, skipping frontend integration.");
            return data;
        }

        logger.accept("5. Tool-driven frontend integrator is validating browser/backend contracts.");
        String objective = """
                Review and, where necessary, fix the real frontend/backend integration of this project.

                Product requirements:
                %s

                Required contract:
                %s

                Inspect templates, JavaScript, CSS, controllers and request/response DTOs before deciding whether edits
                are needed. Verify DOM selectors, event handlers, fetch URLs, HTTP methods, payload fields, response
                fields, template variables, redirects and visible error states. Do not add decorative or meaningless
                network calls. If changes are needed, keep both sides of the contract coherent. Run compile_main after
                the latest edit and call complete_stage only when the project compiles.
                """.formatted(data.prd, data.contract);

        boolean completed = workspaceAgentRuntime.run(
                data,
                WorkspaceAgentMode.FRONTEND_INTEGRATION,
                Set.of(),
                objective,
                logger);
        if (!completed) {
            // 前端审查是增强阶段；失败候选已经由工具运行时回滚，不应阻断后续测试与最终验证。
            logger.accept("   Frontend integration did not complete; preserved the last compiling production candidate.");
        }
        return data;
    }

    private boolean hasFrontend(SoftwareStudioWorkflowData data) {
        return data.codes.stream()
                .filter(code -> code != null && code.filename() != null)
                .map(code -> code.filename().replace('\\', '/').toLowerCase())
                .anyMatch(path -> path.endsWith(".html") || path.endsWith(".js") || path.endsWith(".css"));
    }
}
