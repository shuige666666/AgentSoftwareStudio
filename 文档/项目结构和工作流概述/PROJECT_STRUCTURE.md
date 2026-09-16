# 项目结构与包边界

本文档记录相对稳定的包职责和代码放置规则，避免新增代码时放错位置。详细执行顺序见[工作流概述](./WORKFLOW.md)，目标改造见[重构方案总览](../SoftwareStudio-5生成质量缺陷与开源方案对比重构建议-20260819.md)。

> 2026-09-16 对照 `improve2-realTools-fail`、HEAD `b26efe3` 修正主链相关描述；本次不是全仓库分层合规审计。[返回文档导航](../README.md)。

## 包职责

`Controller`：HTTP 接口和 SSE 适配层。只负责接收请求、返回结果、推送日志，不直接写生成逻辑。

`Service`：主要业务行为层。生成、校验、修复、源码管理、工作区操作、指标统计等实现都优先放这里；跨层传递的公共数据类型不放在 `Service` 中。

`Service/Workflow`：主流程编排、节点服务、共享任务状态和运行记录。当前主链由 SoftwareStudioWorkflowService 顺序调用服务，不通过 LangGraph 图调度。

`Service/Generation`：生成计划及代码生成入口。当前 BatchGenerationService 将完整项目目标交给真实工具会话，不按批次独立并发生成。

`Service/AgentRuntime`：模型—工具执行循环、会话消息、工具注册、上下文压缩和候选同步。

`Service/Contract`：接口契约、前后端契约、跨文件一致性校验。

`Service/Source`：源码路径规范化、源码集合更新、`SourceCode` upsert 规则。

`Service/Workspace`：项目目录、文件落盘、从磁盘加载项目文件，以及规划产物和运行摘要的持久化。

`Service/Metric`：LLM token、缓存命中率、耗时等观测指标。只做统计，不改变 prompt 或生成策略。

`Service/Benchmark`：基准用例注册、质量判定和批次执行逻辑。基准请求、报告和评估结果等数据类型归入 `Model/Benchmark`。

`Agent`：流程中真正的智能体角色，以及直接支撑单次模型调用的执行器。Agent 负责组织 prompt、调用模型、解析结果。

`Model`：统一保存跨层传递的数据模型，包括 record、与 record 强关联的枚举，以及统一响应类。按用途细分为：

- `Model/Benchmark`：基准用例、门禁、请求、评估结果和聚合报告。
- `Model/Workflow`：整体工作流的运行结果。
- `Model/Generation`：PRD、项目结构、文件蓝图、生成批次和源码；其中 `Model/Generation/Contract` 保存接口、视图与前后端契约。
- `Model/Repair`：代码修复指令和修复结果。
- `Model/Metric`：单次 LLM 用量与聚合用量快照。
- `Model/Preview`：生成项目的预览状态。
- `Model/Response`：HTTP 统一响应结构。

`Tool`：外部工具或基础设施封装，例如 Docker 沙箱。应保持工具属性，不承载业务流程判断。

`Config`：Spring Bean 和模型配置装配。`Config/Jackson` 保存模型 JSON 解析需要的 Jackson 适配实现。不写业务逻辑。

## 放置规则

新增类先按职责判断，而不是按调用方或 Java 语法形式判断。

智能体角色放 `Agent`；HTTP 入口放 `Controller`；外部工具封装放 `Tool`；业务行为放 `Service` 的具体子包；公共数据模型放 `Model` 的对应用途子包。

对外暴露或在 Controller、Service、Agent 之间传递的 record 应放入 `Model`。仅供单个实现内部计算使用的 private record 可留在原实现中，不强制迁移。

`Model` 不依赖 `Controller`、`Service` 或 `Tool` 中的行为实现。需要多个模型共用的数据类型应提升为 `Model` 顶层类型，不要继续作为某个 Service 的 public 嵌套类型。

不要把 token 统计、文件路径处理、源码落盘、契约校验等横切逻辑放进 `Agent`。

源码集合更新统一走 `Service/Source`，避免各处重复实现文件名匹配和 upsert。

指标采集属于观测层，优化缓存或 prompt 结构时应另起改动，避免污染基线。
