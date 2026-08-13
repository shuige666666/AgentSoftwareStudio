package com.core.multiAgentSoftwareStudio.Service.Repair;

import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairEvidence;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairProgress;
import org.springframework.stereotype.Service;

/**
 * 只根据真实编译、测试和契约证据判断修复进展，不采信 Agent 的主观完成声明。
 */
@Service
public class RepairProgressEvaluator {

    public RepairProgress compare(RepairEvidence baseline, RepairEvidence candidate) {
        if (candidate != null && candidate.passed()) {
            return RepairProgress.PASSED;
        }
        if (baseline == null || candidate == null) {
            return RepairProgress.NO_PROGRESS;
        }
        if (candidate.stageScore() > baseline.stageScore()) {
            return RepairProgress.PROGRESSED;
        }
        if (candidate.stageScore() < baseline.stageScore()) {
            return RepairProgress.REGRESSED;
        }
        if (candidate.failureKind() == baseline.failureKind()
                && candidate.remainingProblemCount() < baseline.remainingProblemCount()) {
            return RepairProgress.PROGRESSED;
        }
        return RepairProgress.NO_PROGRESS;
    }

    /**
     * 数值越大表示真实验证走得越远，契约通过才是最终成功。
     */
    public int stageScore(FailureKind kind) {
        if (kind == null) {
            return 0;
        }
        return switch (kind) {
            case BUILD_PROFILE, ARCHITECTURE -> 1;
            case MAIN_COMPILE -> 2;
            case TEST_COMPILE -> 3;
            case SPRING_CONTEXT, IMPLEMENTATION -> 4;
            case TEST_DISCOVERY, TEST_CODE, TEST_ASSERTION -> 5;
            case CONTRACT -> 6;
            case NONE -> 7;
            case ENVIRONMENT, UNKNOWN -> 0;
        };
    }
}
