# 软件生成工作流

> 源码核对：2026-09-16，分支 `improve2-realTools-fail`，HEAD `b26efe3`。本文描述该版本已接入的主链，未重新运行生成或构建；重构目标另见[方案总览](../SoftwareStudio-5生成质量缺陷与开源方案对比重构建议-20260819.md)。
>
> [文档导航](../README.md) · [项目结构与包边界](./PROJECT_STRUCTURE.md)

## 主流程概览

当前入口是 [SoftwareStudioWorkflowService.generateProjectWithResult](../../src/main/java/com/core/multiAgentSoftwareStudio/Service/Workflow/SoftwareStudioWorkflowService.java)，在 Java 方法中顺序调用服务，不通过 LangGraph4j 图执行这条主链。项目保留了部分旧节点及模型调用封装，类名中含 Node 或 LangGraph 不代表它仍是当前主流程调度器。

```text
用户请求
-> 需求分析形成 PRD
-> 初始化真实工作区，保存需求产物
-> 架构设计 -> 契约设计，逐步保存规划产物
-> 切片规划 -> 批次规划，保存计划并建立共享修复预算
-> 一个 IMPLEMENT 工具会话实现完整生产代码
-> 有候选文件时：平台补齐工程默认值，执行全项目编译与修复
-> 编译通过后：前端联调会话 -> 测试生成会话
-> 全项目编译、测试与最终契约检查，必要时进入工具驱动修复
-> 保存运行摘要，返回源码、项目路径、验证结果和用量
```

切片和批次在此版本中用于上下文、预算估算及报告，不是“每批生成—每批验收”的执行边界。没有候选文件时跳过编译修复；外层编译修复未通过时不进入前端联调和测试生成。测试编写会话发生被捕获的运行时异常后，仍交由外层测试与修复处理已有候选。

## 主要服务职责

| 服务 | 当前主链中的职责 |
|---|---|
| RequirementNodeService / ArchitectureNodeService / ContractNodeService | 分别形成 PRD、架构和契约，尚未合并为目标方案中的 PlanningAgent |
| SlicePlanNodeService / BatchPlanNodeService | 形成规划上下文；不逐切片或逐批次调用独立生成与验收 |
| PersistenceNodeService | 初始化工作区、保存规划产物、同步完整源码、保存运行摘要 |
| BatchGenerationService | 将需求、结构、批次和契约组成完整项目目标，调用 IMPLEMENT 会话；不是逐批并发生成器 |
| WorkspaceAgentRuntime / WorkspaceToolRegistry | 执行模型与真实工具循环，维护会话消息、工具结果及候选同步 |
| FrontendReviewNodeService / TestGenerationNodeService | 在外层编译通过后依次联调前端、编写测试，当前仍是独立工作阶段 |
| ToolDrivenRepairService | 在编译和测试阶段应用工程默认值、保存候选、执行沙箱验证并根据真实失败修复 |
| RunJournalService / LlmUsageMetricsService | 汇总运行轨迹和用量，供返回结果及报告使用 |

旧文档中的 BatchValidationNodeService、VerificationNodeService、EvaluationNodeService 和 ProjectRepairService 不属于上述入口的当前调用链，不应照旧节点清单恢复调用。

## 状态、文件与已保存产物

工作流共享任务数据集中在 `SoftwareStudioWorkflowData`，包含需求、架构、契约、计划、项目路径、候选源码、验证结果及修复预算等。工具会话另有自己的消息历史，不能把任务对象等同于全部模型上下文。

工作区在 PRD 之后就初始化，并非结束时才统一落盘。工具在真实项目中修改文件，运行时从磁盘同步源码集合；平台验证准备阶段也会将归一化后的完整候选写入同一工作区。操作内存源码集合时复用 `Service/Source`，不要以过期内存内容覆盖工具已经写入的文件。

[PlanningArtifactPersistenceService](../../src/main/java/com/core/multiAgentSoftwareStudio/Service/Workspace/PlanningArtifactPersistenceService.java) 已将原始需求、PRD、架构、契约及切片／批次计划保存到生成项目的 `.software-studio/` 目录；部分规划同时有 JSON 与可读 Markdown。主流程正常进入收尾时保存 `project-profile.json` 和 `run-summary.json`。这不等于目标方案中的运行说明和交付报告已经全部完备，也不代表中断后已具备自动恢复能力。

## 验证与修复边界

当前 IMPLEMENT 目标仍要求只写生产代码、调用 compile_main，并通过 complete_stage 结束会话。即使会话未主动完成，只要保留候选，外层仍会尝试编译修复；因此不能把 Agent 的停止状态直接当成项目验证结果。

[ToolDrivenRepairService](../../src/main/java/com/core/multiAgentSoftwareStudio/Service/Repair/ToolDrivenRepairService.java) 先通过 `compileAndRepair` 处理生产编译；后续 `testAndRepair` 对候选重新编译、执行全量已有测试，再进行最终契约／质量策略检查。编译、测试和契约修复共享同一 run 级预算，不按切片复制额度。现有检查通过也不能自动证明任意用户需求已全部实现。

目标方案计划把开发、测试、联调和修复合入连续 DevelopmentAgent 会话，并引入新的 checkpoint 协议；这些内容属于[待实施设计](../生成质量重构/02-目标架构与设计约定.md#section-7-1)，不能写成当前已有行为。

## 维护约定

主链顺序、角色调用、持久化或结果判断发生变化时同步本文，并更新核对版本；单个方法内部调整不必扩写流程图。契约检查继续收口于 `Service/Contract`，指标采集属于观测层，前端流式页面不承担工作流决策。
