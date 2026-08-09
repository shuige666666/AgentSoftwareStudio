package com.core.multiAgentSoftwareStudio.Model.Workflow;

import java.io.Serializable;

/**
 * 保存 Maven 测试日志中可确定提取的测试执行总量。
 */
public record TestSummary(
        int testsRun,
        int failures,
        int errors,
        int skipped,
        boolean summaryPresent) implements Serializable {

    private static final long serialVersionUID = 1L;

    public static TestSummary empty() {
        return new TestSummary(0, 0, 0, 0, false);
    }

    public boolean executedAndGreen() {
        return summaryPresent && testsRun > 0 && failures == 0 && errors == 0;
    }
}
