package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentMode;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentRunResult;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentRuntime;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.function.Consumer;

/**
 * 让测试 Agent 读取真实生产代码、编写行为测试，并用真实测试执行结果闭环校验。
 */
@Service
public class TestGenerationNodeService {

    private final WorkspaceAgentRuntime workspaceAgentRuntime;

    public TestGenerationNodeService(WorkspaceAgentRuntime workspaceAgentRuntime) {
        this.workspaceAgentRuntime = workspaceAgentRuntime;
    }

    /**
     * 在完整生产项目上编写高价值测试；测试不通过时由同一会话依据最新输出继续修改。
     */
    public SoftwareStudioWorkflowData execute(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        logger.accept("6. Tool-driven test author is writing and running behavioral tests.");
        long before = testFileCount(data);
        String objective = """
                Add a focused, maintainable behavioral test suite for the completed project.

                Product requirements:
                %s

                Architecture:
                %s

                Required contract:
                %s

                Inspect production code and existing tests first. Test important success paths, validation, not-found or
                conflict behavior, persistence/service behavior, and web/API contracts that the requirements actually
                demand. Prefer a small set of meaningful tests over broad shallow coverage. Do not weaken production
                behavior merely to make tests pass. Run run_tests after the latest edit. If the newest output proves a
                production-code or Spring wiring defect that this test-only session cannot edit, preserve the focused
                tests and call report_blocker with that concrete root cause so the implementation repairer can take over.
                Call complete_stage only when the real test suite passes.
                """.formatted(data.prd, data.structure, data.contract);

        WorkspaceAgentRunResult result = workspaceAgentRuntime.runDetailed(
                data,
                WorkspaceAgentMode.TEST,
                Set.of(),
                objective,
                logger);
        long after = testFileCount(data);
        data.generatedTestFileCount += (int) Math.max(0, after - before);
        data.admittedTestFileCount += (int) Math.max(0, after - before);
        if (!result.completed()) {
            logger.accept("   Test author handed off its checkpoint: stop=" + result.stopReason()
                    + ", turns=" + result.modelTurns()
                    + ", toolCalls=" + result.toolCalls()
                    + ", changedFiles=" + result.changedFiles().size() + ".");
        }
        return data;
    }

    private long testFileCount(SoftwareStudioWorkflowData data) {
        return data.codes.stream()
                .filter(code -> code != null && code.filename() != null)
                .filter(code -> code.filename().replace('\\', '/').startsWith("src/test/"))
                .count();
    }
}
