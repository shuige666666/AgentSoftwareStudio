# 项目结构与包边界

本文档只记录相对稳定的包职责，避免新增代码时放错位置。详细主流程如果需要，建议另写文档，不和包边界混在一起。

## 包职责

`Controller`：HTTP 接口和 SSE 适配层。只负责接收请求、返回结果、推送日志，不直接写生成逻辑。

`Service`：主要业务服务层。生成、校验、修复、源码管理、工作区操作、指标统计等都优先放这里。

`Service/Workflow`：LangGraph 工作流编排、节点服务、共享状态。

`Service/Generation`：批次规划后的代码生成执行逻辑。

`Service/Contract`：接口契约、前后端契约、跨文件一致性校验。

`Service/Source`：源码路径规范化、源码集合更新、`SourceCode` upsert 规则。

`Service/Workspace`：项目目录、文件落盘、从磁盘加载项目文件。

`Service/Metric`：LLM token、缓存命中率、耗时等观测指标。只做统计，不改变 prompt 或生成策略。

`Agent`：流程中真正的智能体角色，以及直接支撑单次模型调用的执行器。Agent 负责组织 prompt、调用模型、解析结果。

`Pojo`：跨模块传递的数据结构，例如 PRD、项目结构、契约、源码、修复结果。

`Tool`：外部工具或基础设施封装，例如 Docker 沙箱。应保持工具属性，不承载业务流程判断。

`Config`：Spring Bean 和模型配置装配。不写业务逻辑。

## 放置规则

新增类先按职责判断，而不是按调用方判断。

智能体角色放 `Agent`；HTTP 入口放 `Controller`；数据结构放 `Pojo`；外部工具封装放 `Tool`；其它业务能力优先放 `Service` 的具体子包。

不要把 token 统计、文件路径处理、源码落盘、契约校验等横切逻辑放进 `Agent`。

源码集合更新统一走 `Service/Source`，避免各处重复实现文件名匹配和 upsert。

指标采集属于观测层，优化缓存或 prompt 结构时应另起改动，避免污染基线。
