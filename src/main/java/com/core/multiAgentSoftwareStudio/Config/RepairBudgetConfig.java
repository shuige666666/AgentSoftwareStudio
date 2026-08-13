package com.core.multiAgentSoftwareStudio.Config;

/**
 * 集中维护垂直切片工作流的 LLM 修复预算参数。
 */
public final class RepairBudgetConfig {

    /**
     * 单个项目允许调用修复 Agent 的上限，防止复杂或异常项目无限消耗 LLM 调用。
     */
    public static final int MAX_PROJECT_LLM_REPAIRS = 8;

    /**
     * 在“每个切片一份基础修复机会”之外增加的项目共享额度，用于继续处理逐层暴露的测试或契约错误。
     */
    public static final int EXTRA_REPAIRS_BEYOND_SLICE_COUNT = 2;

    /**
     * 单个切片的修复次数上限。
     */
    public static final int MAX_REPAIRS_PER_SLICE = 4;

    /**
     * 为全部切片验收后的最终全项目验证预留的修复次数；阻塞切片无法继续时允许借用。
     */
    public static final int RESERVED_FOR_FINAL_VERIFICATION = 1;

    /**
     * 同一个基线失败允许产生退化候选并回滚的次数；首次回滚后换一种方案，连续第二次退化才停止。
     */
    public static final int MAX_REGRESSION_ROLLBACKS_PER_FAILURE = 2;

    private RepairBudgetConfig() {
    }
}
