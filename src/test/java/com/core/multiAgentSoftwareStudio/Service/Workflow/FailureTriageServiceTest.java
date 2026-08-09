package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairTarget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureTriageServiceTest {

    private final FailureTriageService service = new FailureTriageService();

    /**
     * 测试编译和测试发现问题归 TestWriter 所有，并继续消耗共享预算。
     */
    @Test
    void routesTestCompileFailureToTests() {
        SoftwareStudioWorkflowData data = data(FailureKind.TEST_COMPILE, "cannot find MockBean");

        var decision = service.decide(data);

        assertEquals(RepairTarget.TESTS, decision.target());
        assertTrue(decision.retryable());
        assertTrue(decision.llmAllowed());
    }

    /**
     * 测试运行期的 Mockito 误用同样归 TestWriter 所有。
     */
    @Test
    void routesTestCodeFailureToTests() {
        SoftwareStudioWorkflowData data = data(FailureKind.TEST_CODE, "InvalidUseOfMatchersException");

        var decision = service.decide(data);

        assertEquals(RepairTarget.TESTS, decision.target());
        assertTrue(decision.retryable());
    }

    /**
     * 环境错误必须停止修改源码，也不能调用任何修复 Agent。
     */
    @Test
    void stopsEnvironmentFailureWithoutLlm() {
        SoftwareStudioWorkflowData data = data(FailureKind.ENVIRONMENT, "Cannot connect to Docker daemon");

        var decision = service.decide(data);

        assertEquals(RepairTarget.ENVIRONMENT, decision.target());
        assertFalse(decision.retryable());
        assertFalse(decision.llmAllowed());
        assertTrue(data.repairStopRequested);
    }

    /**
     * 同一失败修复后再次出现时停止第二次重复调用。
     */
    @Test
    void stopsRepeatedFailureFingerprint() {
        SoftwareStudioWorkflowData data = data(FailureKind.MAIN_COMPILE, "App.java:[10,2] cannot find symbol");
        service.decide(data);
        data.pendingFixLog = "App.java:[11,2] cannot find symbol";

        var decision = service.decide(data);

        assertEquals(RepairTarget.STOP, decision.target());
        assertFalse(decision.llmAllowed());
        assertEquals(1, data.repeatedFailureStopCount);
    }

    private SoftwareStudioWorkflowData data(FailureKind kind, String log) {
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test", 3);
        data.pendingFailureKind = kind;
        data.pendingErrorType = kind.name();
        data.pendingFixLog = log;
        data.shouldFix = true;
        return data;
    }
}
