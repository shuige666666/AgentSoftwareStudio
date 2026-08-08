# 分析

## 核心结论

当前问题确实具有结构性，但不是 LangGraph4j 或“多 Agent”方向选错了，也不需要推倒重来。真正的问题是：系统表面上是图工作流，实际仍是“瀑布式生成 + 最终统一修复”。

当前主链路明确采用“所有代码生成完 → 统一生成测试 → 统一落盘 → 统一验证 → 所有错误交给 Debugger”的方式，[SoftwareStudioWorkflowService.java (line 138)](C:/My Space/Other Projects/agent 软件开发小组/agent-software-studio/src/main/java/com/core/multiAgentSoftwareStudio/Service/Workflow/SoftwareStudioWorkflowService.java:138)。前面任何契约偏差都会积累到最后，而 Debugger 只能根据最终日志猜测应该修改实现、测试、契约还是架构。

## 已确认的主要问题

第一，成功判定不可信。[EvaluationNodeService.java (line 35)](C:/My Space/Other Projects/agent 软件开发小组/agent-software-studio/src/main/java/com/core/multiAgentSoftwareStudio/Service/Workflow/Node/EvaluationNodeService.java:35)依靠 `BUILD SUCCESS`、`Failures: 0` 等字符串判断。找不到测试摘要时，甚至直接“视为成功”。本次重新验证的第二个计算器项目就是 `No tests to run` 加 `BUILD SUCCESS`，平台会把没有测试的程序当成合格产物。

第二，检查发现问题却不阻断流程。[BatchValidationNodeService.java (line 35)](C:/My Space/Other Projects/agent 软件开发小组/agent-software-studio/src/main/java/com/core/multiAgentSoftwareStudio/Service/Workflow/Node/BatchValidationNodeService.java:35)和[PersistenceNodeService.java (line 31)](C:/My Space/Other Projects/agent 软件开发小组/agent-software-studio/src/main/java/com/core/multiAgentSoftwareStudio/Service/Workflow/Node/PersistenceNodeService.java:31)只是积累 warning，然后继续生成、落盘。于是 `package ...`、缺少接口、缺少 Bean 等本来可以确定性阻断的问题，仍会进入昂贵的最终修复。

第三，失败分类能力过弱。现在只有编译、运行、测试三种粗粒度字符串类型，最终都进入同一个[DebuggerAgent.java (line 12)](C:/My Space/Other Projects/agent 软件开发小组/agent-software-studio/src/main/java/com/core/multiAgentSoftwareStudio/Agent/DebuggerAgent.java:12)。但实际失败至少包括：

- 实现错误：代码逻辑、Bean 装配、前后端行为不一致。
- 测试错误：测试断言过窄，例如只接受引号字符串、不接受 JS 模板字符串。
- 契约错误：`RESTART` 究竟是方向还是独立命令没有定义。
- 架构错误：要求 Node 14，却生成 Spring Boot 静态资源结构。
- 环境错误：Java、Mockito、Byte Buddy 或目标运行时不兼容。

没有正确分类，就必然出现“越修越偏”或者反复修改错误文件。

第四，平台自己的关键链路缺少测试。当前聚焦测试共 5 个，全部通过，但只覆盖上下文构建和 LLM 指标；工作流路由、成功判定、契约门禁、失败分类和修复循环都没有自动回归保护。文档也明确记录，最近数次生成质量改动没有经过系统效果验证。

## 建议的目标架构

最优先应把“批次生成”深化成“垂直切片交付 Module”：

```
选取一个可验收功能切片
→ 在真实工作区实现代码和测试
→ 执行确定性质量门禁
→ 编译、测试、契约与行为验证
→ 生成结构化 VerificationResult
→ Failure Triage 分类
   ├─ implementation → 回实现
   ├─ test           → 回测试
   ├─ contract       → 回契约
   ├─ architecture   → 回架构
   └─ environment    → 停止改源码，处理环境
→ 切片通过后再进入下一片
→ 最终全量验证
```

这并不要求删除现有 PM、Architect、Developer、TestWriter 和 Debugger。需要改变的是它们的编排方式：从“一人完成一段流水线”变成“围绕一个切片共同收敛”。

## 推荐实施顺序

1. 先建立生成质量基准集。准备约 10～20 个固定任务，记录首次通过率、最终通过率、假成功率、平均修复次数、需求覆盖率和成本。否则继续修改提示词也无法知道质量是否真的提升。
2. 引入结构化 `VerificationResult` 和 `FailureTriage`，禁止再由工作流直接解析 Maven 日志文本。把 `No tests to run`、占位源码、缺少 Spring Bean、目标 Java 版本不兼容、Node 项目缺少 `package.json` 等升级为硬门禁。
3. 将“全部生成后验证”改为“每个垂直切片落盘并验证”。这是改善修复成功率最大的一步，因为它缩小了错误上下文，也防止前序偏差持续扩散。
4. 引入 `ProjectProfile` 和标准工程模板，例如 Java 8 Spring Boot 2、Java 17 WebSocket、Node 14 前后端分离。LLM 主要填写业务实现，不再从零猜 Maven、依赖、目录和启动方式。
5. 最后补充持久化 Run Journal，保存每次验证证据、失败分类、修改 diff、修复结果和产物快照，用于失败回放与质量回归。

完整的 Before/After 架构候选已经整理成报告：[架构评审报告](C:/tmp/architecture-review-agent-software-studio-20260710-110116.html)。自动打开因报告引用 Tailwind/Mermaid CDN 被安全策略阻止，但文件已经生成，可以本地打开。

综合判断：架构不需要全部推翻，LangGraph 编排、结构化 Contract 和批次计划都可以保留；需要重构的是生成粒度、成功定义、验证 Interface 和返工路由。最值得先深入的是“垂直切片交付 Module”。



# 已完成的优化

## 1 建立生成质量基准集

第一步已落地，而且首个样本已经真实调用 LLM 跑完。

新增了显式基准运行器：它会复用完整生成工作流，保存平台自判、独立门禁、LLM 用量、生成目录和报告 JSON，而不会在日常单元测试中消耗额度。

主要入口在：

- [BenchmarkRunnerService.java](C:/My Space/Other Projects/agent 软件开发小组/agent-software-studio/src/main/java/com/core/multiAgentSoftwareStudio/Service/Benchmark/BenchmarkRunnerService.java)
- [BenchmarkQualityEvaluator.java](C:/My Space/Other Projects/agent 软件开发小组/agent-software-studio/src/main/java/com/core/multiAgentSoftwareStudio/Service/Benchmark/BenchmarkQualityEvaluator.java)
- [SoftwareController.java](C:/My Space/Other Projects/agent 软件开发小组/agent-software-studio/src/main/java/com/core/multiAgentSoftwareStudio/Controller/SoftwareController.java)

启动服务后可调用：

```
Invoke-RestMethod 'http://127.0.0.1:8080/api/SoftwareStudio/benchmarks/run' `
  -Method Post -ContentType 'application/json' `
  -Body '{ "caseIds": ["calculator-web-basic"], "maxRetries": 2 }'
```

- 指定一个 `caseId`，就真实生成并验证 1 个项目。不传 `caseIds`，就依次跑全部 3 个项目。
- 每个项目当前默认 `maxRetries=2`，表示一次初始生成后，最多再经历一次修复回环；它不是重复抽样两次。

（下方的信息为旧的信息，可能和现在的实际信息有所不同）

首轮真实结果：

- 任务：`calculator-web-basic`
- 总耗时：381 秒
- LLM 调用：20 次，均成功
- Token：72,185
- 生成测试：12 个中 10 个通过
- 平台自判：失败
- 独立质量门禁：失败
- 假成功率：暂不可计算——本轮没有平台自判成功的样本，分母为 0。

报告已保存到：E:\Study\AI Project Study\前期的小项目学习\SoftwareStudio-5\benchmark-results

生成的结果（中文翻译版）

```json
{
  "测试开始时间": "",
  "测试结束时间": "",
  "测试案例列表": [
    {
      "案例标识": "",
      "平台执行成功": false,
      "独立质量检查通过": false,
      "人工审核结论": "未审核",
      "生成项目路径": "",
      "总耗时毫秒": 0,
      "质量检查": {
        "总体通过": false,
        "检查项列表": [
          {
            "质量门槛": "",
            "是否通过": false,
            "检查依据": ""
          }
        ]
      },
      "调用使用情况": {
        "调用总次数": 0,
        "成功调用次数": 0,
        "失败调用次数": 0,
        "调用累计耗时毫秒": 0,
        "输入缓存命中Token数": 0,
        "输入缓存未命中Token数": 0,
        "输出Token数": 0,
        "Token总数": 0
      },
      "工作流错误": null
    }
  ],
  "平台执行成功数量": 0,
  "独立质量检查通过数量": 0,
  "假成功数量": 0,
  "假成功率": 0.0,
  "报告文件路径": ""
}
```

