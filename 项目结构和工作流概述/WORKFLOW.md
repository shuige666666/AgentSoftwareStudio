# 软件生成工作流

本文档记录当前主流程的节点级结构和关键约定，目的是降低后续维护和 AI 辅助优化时的理解成本。这里不描述每个方法的实现细节；具体逻辑以代码为准。

## 主流程概览

当前项目生成由 `SoftwareStudioWorkflowService` 使用 LangGraph4j 编排。一次请求大致经过：

```text
用户请求
-> 需求分析
-> 架构设计
-> 契约设计
-> 批次规划
-> 批次生成 / 批次校验循环
-> 测试生成
-> 前端审查
-> 项目落盘
-> 沙箱编译运行
-> 结果评估
-> 可选修复循环
-> 返回源码结果
```

## 节点职责

`RequirementNodeService`：调用产品经理 Agent，把用户请求整理成 PRD。

`ArchitectureNodeService`：调用架构师 Agent，生成项目结构和文件蓝图。

`ContractNodeService`：调用契约 Agent，生成接口、视图、前端调用等结构化契约。

`BatchPlanNodeService`：根据项目结构规划生成批次，通常按基础层、服务层、控制层、前端等顺序推进。

`BatchGenerationService`：按当前批次生成文件；同一批次内可以并发调用开发 Agent。

`BatchValidationNodeService`：对当前批次做轻量校验，发现跨文件结构或契约问题时记录 warning。

`TestGenerationNodeService`：在生产代码批次完成后统一生成测试代码。

`FrontendReviewNodeService`：如果项目包含前端文件，则检查 DOM、事件绑定、可见性切换和 fetch 调用等前端一致性问题。

`PersistenceNodeService`：统一做项目级契约校验，并把源码写入工作区目录。

`VerificationNodeService`：在沙箱中执行编译、运行或测试相关验证。

`EvaluationNodeService`：判断验证结果是否成功，决定结束还是进入修复。

`ProjectRepairService`：调用调试 Agent 生成修复结果，并把修复写回源码集合和磁盘。

## 共享状态

工作流共享状态集中在 `SoftwareStudioWorkflowData` 中。节点之间通过该对象传递 PRD、项目结构、契约、生成计划、源码集合、项目路径、验证结果、待修复日志、当前重试次数等信息。

新增节点时，优先把跨节点状态显式加入 `SoftwareStudioWorkflowData`，不要依赖节点之间的隐式共享变量。

## 关键约定

生成阶段按批次推进，每个批次生成后只做轻量校验；完整编译运行和修复放到所有批次完成之后。

修复流程刻意延后到全项目生成完成后再进行。这样 Debugger Agent 能看到更完整的项目上下文，也能减少“每个阶段都修一次”带来的额外模型调用成本。

源码集合的新增和覆盖应统一走 `SourceCodePathService.upsertSourceCode`，避免同一路径出现多份 `SourceCode`。

契约相关的一致性检查应收口在 `Service/Contract`，不要散落在各个 Agent 或节点中。

LLM token、缓存命中率和耗时属于观测指标。指标采集不应改变 prompt 内容、生成顺序或缓存策略，避免污染后续优化对比基线。

前端流式页面只展示后端日志事件，不承担工作流判断逻辑。

## 后续优化建议

调整主流程时，优先同步更新本文件中的节点顺序和关键约定。

如果只是修改某个节点内部实现，不需要更新本文档，除非改变了节点职责、状态字段或流程分支。
