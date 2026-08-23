# SoftwareStudio-5 生成质量缺陷、开源项目对比与重构方案

> 分析日期：2026-08-19  
> 原项目：`SoftwareStudio-5`，分支 `improve2-realTools-fail`，提交 `5cfeb92ea3c1bea1b59e87cc659276c48efcb787`  
> 参考项目：OpenCodeReview、mini-SWE-agent、Codex CLI、Aider  
> 分析方法：以当前源码调用链、真实生成产物、工具轨迹和基准报告为主，不把“调用过工具”“产生了 diff”或 `platformSuccess` 当作质量证明。
> 补充更新：2026-08-23，增加“平台确定性 Spring Boot 脚手架”设计、实施优先级与独立消融指标；原始运行证据的提交边界不变。

## 一、核心结论

当前项目生成质量差，根因并不是单纯的“模型能力不够”，而是整个生成控制面同时存在语义规划失真、执行粒度失配、上下文浪费、编辑工具摩擦、验证自证循环和修复反馈过粗等问题。模型收到的目标可能在进入编码阶段前就已经被错误默认值污染，随后又要在一个超长、重复、全项目级目标中完成十几个到二十几个跨层文件；工具接口迫使它反复读取和处理 SHA 冲突，而真正能证明业务正确的外部验收又很弱。结果是：模型在有限轮次内忙于建立工作区和满足错误契约，尚未形成“实现—验证—诊断—修复”的有效闭环便耗尽预算。

最直接的证据是短链接样例。规划描述明明写着“通过 fetch 发送 POST 请求创建短链接”，结构化 `FrontendCallContract` 却因为缺失字段被构造器静默补成 `GET /`。后续生成代码真的增加了 `fetch('/').catch(() => {})` 和 `@GetMapping("/")`，从而让代码与错误契约保持一致。这不是普通的编码疏漏，而是上游语义错误经过“契约校验”被系统性放大的实例。

因此建议采用“保留可用基础设施、重构生成控制面”的方案，而不是继续给现有单会话循环增加轮次或堆提示词。应保留工作区持久化、真实编译测试、写版本校验、工具审计日志等已有资产；重写规划语义门、执行清单、里程碑调度、上下文构建、补丁工具、独立验收和诊断式修复。总体上属于中到大型结构性重构，但不需要推倒 Spring Boot 接口层或所有领域模型。重构第一版的正式能力边界收窄为 Java Spring Boot Maven Web/REST 项目；纯 Java Maven 与原生 Java 只保留为实验能力，不能共享正式成功声明。

其中，Spring Boot 工程 Bootstrap 不应继续交给模型从空目录逐文件创建。平台应把已验证的 `ProjectScaffoldSpec` 渲染成版本化、可复现的本地脚手架，在零模型轮次内生成 `pom.xml`、应用入口、基础配置、目录和启动性测试，再完成依赖预检与最小编译；Agent 从可构建骨架开始实现领域、接口、前端和业务测试。这项改造会直接减少模型在固定样板、版本选择和基础编译错误上的轮次消耗，属于阶段二最优先的执行侧优化，而不只是增加一道检查。

## 二、分析范围与证据边界

### 2.1 本次核对的仓库快照

| 项目 | 分支 | 提交 | 工作区 | 主要用途 |
|---|---|---|---|---|
| SoftwareStudio-5 | `improve2-realTools-fail` | `5cfeb92e` | clean | 被分析系统 |
| OpenCodeReview | `main` | `f269d0ce` | clean | 执行清单、覆盖状态、压缩和并发调度 |
| mini-SWE-agent | `main` | `25941c89` | clean | 最小 Agent 循环、环境抽象、轨迹与外部基准 |
| Codex CLI | `main` | `f5a3dc55` | clean | 工具策略、沙箱、补丁、并发和持久化事件流 |
| Aider | `main` | `5dc9490b` | clean | RepoMap、架构师/编辑器分离、编辑与测试反馈闭环 |

四个参考项目解决的主要是已有代码库中的审查或修改问题，而 SoftwareStudio-5 的任务是从近乎空的工作区生成跨前端、后端、持久化和测试的完整项目。因此，本报告比较的是控制机制，不直接比较它们的榜单成绩，也不会把某个项目的文件级调度原样套到绿地项目生成上。

### 2.2 运行证据的时效性

当前源码分析对应上述 HEAD。真实轨迹来自：

- `studio-run-logs/短链接服务_20260815_123836/tool-calls.jsonl`
- `studio-run-logs/在线投票系统_20260815_125029/tool-calls.jsonl`
- `ai_generated_projects/短链接服务_20260815_123836`
- `ai_generated_projects/在线投票系统_20260815_125029`

这些产物生成于当前提交之前，适合证明控制机制怎样实际失效，但不能当作当前 HEAD 的重新跑分。最新基准报告 `benchmark-results/benchmark-2026-08-15T04-38-13.184464Z.json` 记录的是更早的 dirty 提交 `d2f36a41`；两个案例都因 `UnsupportedOperationException: No message` 结束，没有项目路径和有效验证结果。当前机器的 Docker daemon 未运行，本次无法补做 Java 17 Docker 全量复测，因此报告不会给出未经验证的“当前通过率”。

### 2.3 重构第一版的正式支持边界

当前 `ArchitectAgent` 声明可选择 `SPRING_BOOT`、`PURE_JAVA_MAVEN` 或 `PURE_JAVA_NATIVE`，但产品经理默认技术栈、六个稳定基准、固定工程模板、主要契约门和项目预览都集中在 Spring Boot。把三类项目继续视为同等支持，会迫使每种类型同时建设里程碑模板、沙箱镜像、编译测试规则、独立 Acceptance 和预览能力，既扩大重构范围，也使质量结论无法归因。

因此，本方案将第一版术语固定为：

- **正式支持项目**：匹配平台固定 Java/Spring Boot/Maven Profile 的 Web 或 REST 项目，拥有完整规划、生成、独立验收和可选预览能力；
- **实验项目**：`PURE_JAVA_MAVEN`、`PURE_JAVA_NATIVE` 等尚无类型专属质量体系的项目，可以尝试生成并保留产物，但不能产生与正式支持项目相同的成功状态或质量声明；
- **平台 Profile**：由平台固定并可复现的 Java 版本、Spring Boot 版本、Maven 构建方式、允许依赖和验证能力，不等于模型自由建议的技术栈。

阶段一至阶段四的实现与基准均以正式支持项目为对象。架构阶段若识别到实验类型，应明确进入 `EXPERIMENTAL` 路径或在编码前提示能力边界，不能静默套用 Spring Boot 门禁。后续扩展其他类型时，必须新增独立 Profile、里程碑模板和验收集，而不是在现有分支中增加几个条件判断便宣称支持。

### 2.4 第一版采用封闭、非交互的需求输入

第一版的目标是先隔离并改善规划、执行、工具和验证控制面，不同时建设用户需求访谈系统。无论前端单次生成还是无人值守批量基准，一个 run 都把初始需求文本视为冻结的 **封闭需求输入**，运行中不创建 `WAITING_FOR_CLARIFICATION` 状态，不向用户追问，也不因为等待回答而占用执行线程或模型预算。

这不等于允许系统继续静默伪造语义。需求未指定且不影响核心行为的细节，可以根据固定平台 Profile 形成 **显式生成假设**并写入 `RequestSpec` 与最终报告；架构或契约对象自身缺失 HTTP method、path、owner 等执行必填字段时，仍由语义门定向重规划，最多一次后以稳定的 `PLANNING_INVALID` 结束。换言之，第一版区分的是“暂不追问用户”和“内部规划必须完整”，前者不能成为后者降级的理由。

批量基准的需求文本和外部 Acceptance Suite 共同构成冻结输入，不允许某次运行临时扩写问题或改变判卷口径。需求访谈、用户回答后的原 run 恢复和多轮产品澄清属于后续产品能力，只有在生成控制面和独立行为质量达到稳定基线后再评估。

### 2.5 两个真实轨迹的量化摘要

| 指标 | 短链接服务 | 在线投票系统 |
|---|---:|---:|
| Agent 会话 | 4 | 4 |
| 模型轮次 / 工具调用 | 60 / 60 | 60 / 60 |
| `MODEL_TURN_LIMIT` 会话 | 4 / 4 | 4 / 4 |
| 上下文压缩 | 40 | 40 |
| 读取 / 列表 / 编辑 | 21 / 11 / 15 | 31 / 14 / 12 |
| Agent 会话内编译 / 测试 / 契约验证 | 6 / 2 / 3 | 0 / 0 / 0 |
| 工具协议错误 | 3 `STALE_FILE` + 1 `EMPTY_FILE` | 5 `STALE_FILE` |
| IMPLEMENT 产出变更文件 | 18 | 24 |
| TEST 会话产出变更文件 | 0 | 0 |

这张表不能证明短链接项目质量高于投票项目，因为外层工作流还会继续验证和修复；它证明的是两个会话都稳定呈现“一轮模型、一次工具”的低吞吐模式，并且投票系统在 60 轮内没有进入任何 Agent 内部验证。相比单纯扩大上限，这更支持重构任务粒度、上下文和工具协议。

## 三、当前系统的真实执行链

`SoftwareStudioWorkflowService.generateProjectWithResult` 当前按以下顺序运行：

```text
用户需求
  -> PRD
  -> 初始化工作区并持久化规划产物
  -> 架构规划
  -> 契约规划
  -> 垂直切片规划（只用于上下文、预算和报告）
  -> 批次规划（只被拼进目标，不作为实际执行边界）
  -> 一个 IMPLEMENT 会话实现全部生产代码
  -> 外层真实编译与修复
  -> 前端审查会话
  -> 测试生成会话
  -> 外层真实测试与修复
  -> 持久化总结
```

这里最关键的结构矛盾是：系统花费模型调用生成了切片和批次，却在 `SoftwareStudioWorkflowService.java:97-108` 明确取消了它们的执行边界。`BatchGenerationService.java:27-55` 随后把 PRD、完整文件蓝图、全部批次和完整契约通过 Java `toString()` 一次性拼成目标，交给一个全项目会话。短链接目标为 17,686 字符，投票系统目标为 26,835 字符；其中大量文件说明、依赖和分组信息重复出现。

这使系统处于一种不利的中间状态：既承担多 Agent 规划的成本和错误传播风险，又没有获得分阶段实施、局部验收和故障隔离的收益。

## 四、为什么生成质量会差：完整缺陷因果链

### 4.1 第一根因：结构化输出只有“能解析”，没有“语义正确”

规划 Agent 主要依赖 JSON 解析和修复，但缺乏规划结果进入下一阶段前的系统语义校验。`ApiEndpointContract.java:21-27` 与 `FrontendCallContract.java:19-23` 把缺失的 HTTP method 和 path 静默改成 `GET` 与 `/`。这种默认值适合无害展示字段，不适合接口协议中的必填字段：缺失信息本应阻断规划，而不是被伪装成合法契约。

短链接轨迹完整展示了污染链：模型描述“POST 创建、GET 查询”，字段丢失后变成多个 `GET /`；生成器又把错误契约当成 required contract；最后实现根路径请求和根控制器以通过契约检查。系统得到的是结构上自洽、业务上错误的结果。

同类风险还包括 `ProjectStructure[rootPackage=null]`、空 `implementedBy`、空 view name/returnedBy，以及文件蓝图依赖不闭合。当前没有统一的 `PlanningValidationService` 对这些结果做跨对象检查，也没有把“错误、警告、可推断值”分级。因此，错误规划被保存成权威事实，并进入后续每一个提示词和校验器。

### 4.2 第二根因：规划粒度与执行粒度脱节

切片规划本来应解决“一个全栈功能怎样从数据层贯通到接口与前端”的问题，但当前切片只用于上下文、预算估算和报告。批次规划也只排序文件，实际实现仍是一口气完成全项目。由此产生三个后果：

第一，模型必须在一个会话中同时管理项目脚手架、实体、Repository、Service、Controller、DTO、静态资源和跨层契约，工作记忆负担远超单个修复任务。第二，任何局部错误都只能等全项目完成后再由外层编译发现，定位范围很大。第三，规划调用的投入没有转化为可验证的进度单位，系统无法回答“哪些能力已经真正完成、哪些仍待实现”。

当前系统其实已经具备“半个脚手架”：`ProjectProfileService` 内置 Java 17 Spring Boot POM 模板，并能确定性补充依赖与 `contextLoads()`；但 `BatchGenerationService.java:44-47` 仍明确要求 Agent 自行创建 build configuration 和 application bootstrap，`ToolDrivenRepairService.java:84-92` 直到完整生产代码生成后才调用 `applySafeDefaults()` 兜底。也就是说，现状是“模型先猜、平台后修”，固定工程结构仍会占用模型上下文、编辑轮次和修复预算。更合理的顺序应是“平台先生成可构建骨架、模型再实现业务、平台最后验证”，并把后置兜底降级为兼容迁移路径。

正确的执行单位不应是单文件，也不应是整个项目，而应是“依赖闭合、具有明确验收条件的能力里程碑”。例如短链接项目可以分为工程脚手架、短码领域与持久化、创建/查询/跳转 API、前端联调、行为测试五个里程碑；每个里程碑只携带相关需求、文件和验收项。

### 4.3 第三根因：上下文压缩压错了对象

`WorkspaceAgentContextCompactor.java:23-51` 在第 6 轮后保留系统消息、原始用户目标、一个确定性摘要和最近 3 个模型轮次。这个机制保护了 assistant/tool 配对，方向是对的；问题在于它始终保留最庞大的静态目标，而只压缩真正记录“刚才读了什么、为什么这么改”的动态历史。

两个最新轨迹中，每个项目都发生 40 次 `CONTEXT_COMPACTED`。压缩发生得频繁，却无法消除 17K/26K 字符的原始目标，也没有按 token 预算选择保留内容。结果是输入成本持续偏高，早期设计决策不断丢失，模型又被摘要要求“重新读取当前文件”，进一步消耗工具轮次。

这里需要的不是更激进地截断聊天，而是改变上下文构成：静态部分只放规范化需求与当前里程碑；代码上下文由 RepoMap 和依赖检索按需注入；失败上下文使用结构化 `FailurePack`；历史按 token 比例保留完整工具轮次。OpenCodeReview 的软/硬 token 阈值和 Aider 的 RepoMap 都比固定“第几轮开始压缩”更符合这一问题。

### 4.4 第四根因：工具协议让模型花预算与接口搏斗

当前工具集包含文件列表、搜索、读取、编辑、diff、编译、测试、契约验证和完成阶段，已经比纯文本生成可靠。但接口仍有明显摩擦：

- `read_files` 以整文件为主，缺少稳定的行范围读取；较大文件只能被整体截断。
- `search_code` 是自定义、大小写敏感的字面搜索，缺少正则、大小写选项、上下文行和成熟的路径过滤。
- `apply_edits` 要求精确 SHA 和唯一 `oldText`，一个文件一次只能表达有限编辑；没有统一的 add/delete/update/move 补丁语法，也没有 dry-run 后逐块报告成功和失败。
- 写入异常时存在恢复会话初始基线的路径，可能把本轮此前已经正确的修改一并撤回；更合理的是文件级原子写或候选快照事务。
- 当前请求显式设置 `parallel_tool_calls=false`，实际轨迹又表现为每个模型轮次恰好一个工具调用。读多个文件、并行搜索等无副作用工作无法合并，轮次上限因此先于工具调用上限耗尽。

短链接轨迹的 60 个工具调用中有 3 次 `STALE_FILE` 和 1 次 `EMPTY_FILE`；投票系统有 5 次 `STALE_FILE`。错误数量本身不是唯一问题，更重要的是每次失败都额外消耗一个模型轮次。投票系统 60 次调用里甚至没有在 Agent 会话内执行一次编译或测试，说明预算在进入验证闭环前已经耗尽。

### 4.5 第五根因：停止条件描述了“预算用完”，没有描述“任务完成”

两个样例都包含 IMPLEMENT、FRONTEND_INTEGRATION、TEST、REPAIR_CONTRACT 四个会话，轮次分别是 24、8、12、16；八个会话全部以 `MODEL_TURN_LIMIT` 结束，且每轮一次工具调用。模型从未通过 `complete_stage` 完成这些阶段。

当前运行时在候选文件存在时会保留未完成结果并交给外层编译，这是合理的容错；但系统缺少一个跨会话、不可变、可恢复的执行清单，无法严格区分：

- Agent 主动完成并提交了哪些里程碑；
- 平台根据独立验证接受了哪些里程碑；
- 哪些只是生成过文件但尚未验证；
- 哪些因预算、工具、模型或基础设施原因失败；
- 恢复运行时哪些项可以安全复用。

OpenCodeReview 的 manifest 价值正在于先冻结“被选中的分母”，再维护 completed、failed、waived、reused 等互斥状态，最终状态只由覆盖率与运行失败决定，而不由评论数量或模型自述决定。SoftwareStudio-5 应把这个思想改造成 `ExecutionManifest`，其 item 是能力里程碑和验收项，而不是审查文件。

### 4.6 第六根因：验证器容易形成“自己出题、自己作答、自己判卷”

现有真实 Maven 编译和测试是重要资产，但独立质量门仍然偏结构化：是否没有 placeholder、是否存在测试源码、测试是否绿色、是否存在 `@SpringBootTest`、契约策略是否通过。`ProjectProfileService.java:201-215` 还会确定性生成只有 `contextLoads()` 的 Spring Boot 测试；这一个 12 行测试就可能同时满足“有测试”和“存在 SpringBootTest”两项门槛，却没有覆盖任何用户故事。

短链接生成项目最终正是只有 `ShortLinkApplicationContextTest.contextLoads()`。它可以证明 Spring 容器能启动，不能证明：相同 URL 是否复用短码、非法 URL 是否返回校验错误、跳转是否累加访问次数、过期链接是否 404、创建/查询接口是否满足响应契约。

契约也主要由上游 LLM 生成，再由正则或静态检查对照同一份契约。只要上游契约错了，代码可以通过“迎合错误契约”获得结构一致性。因此必须引入在实现前冻结的 `AcceptanceSpec` 和不完全暴露给编码 Agent 的外部行为测试。最终成功应定义为：

```text
RunSuccess = ExecutionManifest 全部必需项终态完成
             AND 所有必需 AcceptanceCheck 通过
             AND 不存在未授权 waiver
```

模型是否调用完成工具、生成了多少文件、编译是否通过，都只能作为证据的一部分。

### 4.7 第七根因：修复系统看到的是日志数量，而不是诊断状态

`RepairProgressEvaluator` 主要比较阶段得分和问题数量，`FailureTriageService` 主要对整段规范化日志做指纹。整段日志容易被行号、顺序和噪声扰动；单纯问题数又无法表达“解决一个阻塞编译的根因，同时出现两个低优先级告警”其实是进步。当前三轮加一轮的共享修复额度还没有为最终验收保留独立预算，并会在一次 `NO_PROGRESS` 或 `REGRESSED` 后较早停止或回滚。

更可靠的做法是先将编译、测试、契约和运行时输出解析为稳定诊断集合：

```text
DiagnosticKey = stage + tool + file + symbol + normalized_location + error_code
```

进度由阻塞级诊断是否消失、是否产生新回归、受影响验收项是否转绿共同决定。修复控制器应保存“当前候选”和“历史最佳候选”，把诊断路由给对应所有者，并允许在同一根因上尝试有限的不同假设，而不是简单重放整段失败日志。

### 4.8 第八根因：模型接入层的容错和角色边界不足

逻辑 Agent 使用的抽象会把 SYSTEM 与 USER 文本拼成一个普通字符串，再依赖模型输出 JSON；真正的工具模型虽使用 role 和 tool schema，但工具请求没有面向超时、限流和临时网络错误的系统重试。`tool_choice=auto` 与 `parallel_tool_calls=false` 也是全局固定策略，没有按阶段和模型能力选择。

这导致“模型规划失败”“响应格式失败”“供应商临时失败”“工具执行失败”在上层表现得过于接近。目标架构应设置统一 `ModelGateway`：保留真实角色，规划输出采用严格 schema；只对可重试且幂等的错误做指数退避；记录 provider、模型修订、token、缓存、finish reason 和请求标识；规划模型与编辑模型可以不同，但二者的责任边界必须由类型而不是提示词约定。

模型档位本身也可能影响复杂实现质量，但现有证据不足以把它定为主因。较早的 dirty 基准报告使用逻辑模型承担规划、flash 档编码模型承担 Workspace Agent；两个案例分别消耗 449,746 和 614,416 total tokens，63 次模型请求全部在供应商层成功，工作流却都在形成项目和验证结果前异常退出。这恰好说明“API 调用成功”和“任务成功”是两回事，也说明当前上下文与调度效率很低。在控制面可信之前，直接更换更强模型无法区分模型收益与系统噪声；控制面稳定后，再对 planner/editor 模型做同预算 A/B 才有意义。

### 4.9 第九根因：沙箱既不够可复现，也不够安全

`DockerSandboxService` 已提供超时与容器清理，但当前容器仍以默认网络、root 用户、读写项目挂载和宿主 Maven 仓库挂载运行，缺少 CPU、内存、PID、capability、`no-new-privileges` 和只读根文件系统限制。质量角度上，这会让依赖网络和宿主缓存状态进入实验；安全角度上，也不适合未来扩展为通用命令工具。2026-08-23 的只读测量显示，当前个人 `.m2/repository` 已包含 34,197 个文件、约 2.76 GiB；共享它避免了每个容器重复下载，但也说明目标方案不能改成“每个项目复制一份 Maven 仓库”，否则磁盘会随 run 数快速增长。

因此不能为了模仿 mini-SWE-agent 或 Codex CLI，直接给模型开放宿主机 `shell=true`，但这不等于必须为每个文件动作设计一个专用工具。安全执行有两种成立的模式：一种是工具只接受 argv、禁止 Shell 并校验可执行文件白名单；另一种是在真正隔离的一次性容器中开放完整 Bash，把安全边界下沉到文件系统、网络、身份、资源和挂载策略。对于需要大量探索、组合命令和跨文件操作的 SoftwareStudio-5，后者更符合代码模型习惯，前提是 Bash 只能运行在候选项目沙箱内，隐藏验收和宿主资源不可写也不可见。

如果允许 `bash -lc`，命令前缀白名单并不能形成可靠边界，因为 Bash 本身可以启动其他程序、重定向和解释脚本。此时必须依靠 hardened sandbox，而不是假设命令字符串可被完整理解。Shell 中的 Maven、测试和输出只能用于 Agent 自诊断；最终完成状态仍由平台在独立快照上执行受信任验证后写入。

### 4.10 因果链总结

```text
LLM 规划只有 JSON 结构校验
  -> 必填语义缺失被 GET / 等默认值掩盖
  -> 错误规划被持久化成权威契约
  -> PRD/架构/批次/契约被重复拼成长目标
  -> 一个 Agent 在空工作区实现整个跨层项目
  -> 读取、SHA 冲突和单次工具调用快速消耗轮次
  -> 压缩反复保留巨大静态目标、丢掉早期动态决策
  -> 会话在首次有效验证前达到 MODEL_TURN_LIMIT
  -> 外层修复只能看到粗粒度日志和极小共享预算
  -> contextLoads 与自生成契约提供虚弱质量信号
  -> 最终产物可能“能编译/结构自洽”，却不满足用户故事
```

## 五、四个开源项目分别值得学习什么

### 5.1 OpenCodeReview：把“做了什么”变成可证明的状态机

OpenCodeReview 最适合解决 SoftwareStudio-5 的完成判定和恢复问题。`internal/session/manifest.go` 先注册并 seal 被选中的文件分母，再以稳定 item ID、diff fingerprint 和互斥状态记录 selected、completed、failed、waived、reused；终态由清单覆盖与运行失败计算，不由 LLM 评论数量计算。`internal/agent/identity.go` 在恢复前冻结输入身份，防止把不同 diff 的旧结果错误复用。

它的 `internal/agent/agent.go` 还体现了清晰的控制面：确定性预过滤、预算预估、可选 per-file plan、并发限制、子任务故障隔离、逐项完成登记。`internal/llmloop/loop.go` 使用显式 stop enum、无工具调用提醒、工具预算和到达上限后的 grace round。`internal/llmloop/compression.go` 以 token 的软阈值和硬阈值压缩完整 assistant/tool 轮次，并在压缩失败时保留原上下文。

可直接借鉴的是 Manifest 的不变量、身份指纹、显式停止原因、token 预算压缩和有界文件工具。需要改造的是调度单位：代码审查可按文件隔离，完整项目生成必须按依赖闭合的能力里程碑调度，否则 Controller、Service、DTO、页面被拆开后仍会互相等待。OpenCodeReview 的 scan bundling 也不能被误读成已经解决任意跨文件实现事务。

### 5.2 mini-SWE-agent：保持主循环简单，并把轨迹当作一级产物

mini-SWE-agent 的价值不在功能多，而在主循环足够小、协议足够透明。`src/minisweagent/agents/default.py` 明确维护 system/user 消息、步数、成本与轨迹，在每一步后落盘，即使异常也保留可复现实验记录。`models/utils/retry.py` 对可恢复模型错误执行指数退避，`run/benchmarks/swebench.py` 即使任务异常也保存 trajectory 和 prediction。

其 SWE-bench 配置还把“先检查仓库、复现问题、修改、验证、提交 patch”写成稳定操作协议，并使用外部数据集和预构建实例镜像作为独立判卷方。这比从生成代码自身推导质量门更可信。

SoftwareStudio-5 可以借用它的 `Agent`、`Model`、`Environment` 三层接口、Shell-first 交互、始终落盘的轨迹、可重试异常分类和外部基准思想。但不能照搬 `local.py` 的宿主 shell，也不能把 `docker.py` 的默认容器参数视为安全沙箱。适合本项目的改造是把完整 Bash 放入强化的候选项目容器，同时用平台 checkpoint 负责正式验收。SWE-bench 的成绩衡量已有仓库 bug 修复，不能直接外推到绿地全栈生成。

### 5.3 Codex CLI：工具不是一个函数表，而是一套受策略约束的运行时

Codex CLI 在工程成熟度上最值得参考。`core/src/tools/orchestrator.rs` 将策略检查、审批、沙箱选择、首次执行和允许条件下的升级重试串成统一管道；`sandboxing.rs` 明确区分 Skip、NeedsApproval、Forbidden，并保证升级权限时不丢失原有拒绝读取规则。`parallel.rs` 让工具声明是否支持并发：只读工具可持有读锁并行，写工具持有独占锁，取消时中止在途任务。

`apply-patch` 提供正式 add/delete/update/move 语法和结构化 chunk，明显优于在整文件文本中寻找唯一 `oldText`。`head_tail_buffer.rs` 对长输出保留头尾并标出省略字节；`turn_diff_tracker.rs` 维护精确净 diff；`rollout/src/recorder.rs` 用追加式 JSONL、flush barrier 和写成功后再清除 pending item 的方式保证轨迹耐久。`responses_retry.rs` 则区分连接重试和协议 fallback。

这些机制适合被裁剪后移植到 Java：Shell 执行与结构化 patch 的混合接口、策略与沙箱分层、补丁解析、头尾截断、追加日志和精确 diff。不能照搬整个 Codex 架构，也不能只抄一个通用 shell；Codex 的命令能力之所以可控，是因为策略、权限、沙箱、审批和审计共同存在。SoftwareStudio-5 的候选执行环境可以完全放进一次性容器，但权威工作树不应只存在于容器可写层：平台应先在 Windows 的 `ai_generated_projects/<project_run_id>` 建立项目目录，再把这一个目录受限地绑定挂载到容器。这样可以不复制复杂的人类审批体验，同时仍保留固定挂载边界、平台验收、审计和宿主持久化。

### 5.4 Aider：只给模型相关代码，并让编辑失败可自我纠正

Aider 最适合改善上下文选择与编辑闭环。`aider/repomap.py` 通过 tree-sitter 提取定义和引用，构建代码图并用 PageRank 按当前文件、用户提及和依赖关系排序，再在 token 预算内生成 RepoMap。`base_coder.py` 将完整聊天文件与 RepoMap 分开，避免把整个仓库塞进上下文。

`architect_coder.py` 把“决定改什么”和“按特定编辑格式落地”分给 architect 与 editor；`editblock_coder.py` 先 dry-run search/replace blocks，允许部分块成功，并把未匹配块、相似候选和修复建议反馈给模型。`base_coder.py` 的主闭环是应用更新、记录版本、自动 lint、反思 lint 错误、自动测试、再反思测试错误，并限制反思次数。

SoftwareStudio-5 应优先借鉴 RepoMap、架构师/编辑器分离、模型适配的编辑格式以及 lint/test 反馈闭环。绿地项目开始时没有代码图，可以先从 `PlanIR` 生成预期符号图，随着文件产生再切换到真实语法图。Aider 的交互式自动 commit 和用户 undo 语义不宜原样复制，应改成平台管理的候选快照和里程碑 checkpoint。

## 六、横向对比矩阵

| 维度 | SoftwareStudio-5 当前实现 | OpenCodeReview | mini-SWE-agent | Codex CLI | Aider | 建议 |
|---|---|---|---|---|---|---|
| 任务分解 | 有切片/批次规划，但不作为执行边界 | 文件级选择、计划与覆盖状态 | 单任务线性循环 | 以 turn/tool 调度，不替用户做固定业务切片 | architect 规划后 editor 落地 | 改成依赖闭合能力里程碑 |
| 完成定义 | 会话完成、外层验证与 data.success 混合 | sealed manifest + 状态分区 | 明确 exit/submission | 结构化 turn/rollout 状态 | 编辑、lint、test 反馈 | 引入不可变 ExecutionManifest |
| 上下文 | 固定轮次压缩，永久保留巨大原目标 | token 软/硬阈值，保留完整轮次 | 简单消息历史与可配置截断 | token 估算、成对移除、压缩替换 | RepoMap + 当前文件 | 里程碑上下文 + RepoMap + FailurePack |
| 模型可见工具面 | 10 个专用工具 | 以有界审查工具为主 | Shell-first | Shell/exec + apply_patch 等混合工具 | 编辑协议 + 可运行命令 | 收敛为 Bash、apply_patch、submit_checkpoint、report_blocker |
| 编辑协议 | SHA + 唯一 oldText | 审查工具为主 | 常用 shell/patch | 正式 apply_patch 语法 | 多种 edit format、dry-run、反思 | Bash 负责探索，结构化 patch 负责可靠写入 |
| 工具并发 | 请求关闭并行，轨迹一轮一工具 | 有界文件并发 | 可在一条命令中组合操作 | 工具声明并发，读写锁 | 主要串行编辑 | Bash 内批量只读，patch 写入独占，平台验证可并发 |
| 模型容错 | 工具请求缺少系统重试 | 显式循环停止与压缩失败回退 | 指数退避与异常分类 | 连接重试和协议 fallback | 多次 reflection | 统一 ModelGateway 与幂等重试 |
| 验证 | 编译/测试真实，但行为门弱、自生成契约 | 评论后仍有确定性 post-filter | SWE-bench 外部 oracle | 工具结果真实、策略独立 | lint + 用户命令 + tests | 冻结 AcceptanceSpec + 隐藏行为测试 |
| 修复进度 | 问题数和整段日志指纹 | item 状态与 coverage | 下一轮读取真实输出 | 精确 diff/turn state | lint/test 输出回灌 | 稳定 DiagnosticSet + 最佳候选 |
| 轨迹恢复 | 有 JSONL 与总结，但缺跨阶段清单 | manifest、identity、resume lineage | 每步 trajectory | 耐久 rollout recorder | Git 历史/undo | append-only EventJournal + checkpoint |
| 安全执行 | Docker 有超时，但权限和资源限制弱 | 主要只读审查工具 | 本地 shell 风险高 | 分层 policy/approval/sandbox | 可运行本地命令 | 在 hardened 候选容器开放 Bash，不开放宿主 shell |

## 七、推荐目标架构

### 7.1 总体数据流

```text
UserRequest（封闭、不可在 run 中追问）
  -> RequirementCompiler
       -> RequestSpec（规范化需求 + 显式生成假设）
       -> AcceptanceSpec（实现前冻结的验收规则）
  -> PlanningCompiler
       -> PlanIR（能力、符号、文件、依赖、里程碑）
       -> SemanticGate（失败则重规划，不进入编码）
  -> ExecutionManifest.seal()
  -> BootstrapExecutor（平台执行，零 LLM 轮次）
       -> ProjectScaffoldSpec
       -> ProjectScaffoldService（版本化本地模板 -> Windows 权威工作区）
       -> DependencyResolver / DependencyPreflight / 最小编译
  -> MilestoneScheduler
       -> ContextBuilder（当前里程碑 + RepoMap + 最近诊断）
       -> AgentKernel（Planner/Editor + Bash/Patch/Checkpoint）
       -> VerificationBroker（编译/测试/契约/行为）
       -> RepairController（DiagnosticSet 驱动）
       -> Checkpoint / EventJournal
  -> FinalAcceptance
  -> EvidenceReport
```

### 7.2 六个核心中间模型

`RequestSpec` 只保存经过规范化的用户目标、技术约束、用户故事、非功能约束、显式生成假设和明确的未知项。第一版不因未知项暂停向用户追问：非关键缺省使用平台 Profile 的显式假设；会破坏计划可执行性的未知项产生诊断并触发定向重规划，仍无法解决则以 `PLANNING_INVALID` 结束。任何未知项都不能被伪造成 GET `/` 之类看似合法的必填契约。

`AcceptanceSpec` 在代码生成前建立并冻结。每个用户故事至少映射一个可执行或可静态验证的验收项，包含输入、预期输出、失败语义和重要级别。编码 Agent 可以看到公开接口约束，但不应看到所有隐藏行为测试的具体断言。

`PlanIR` 是规划编译器的结果，而不是 LLM 对象的直接 `toString()`。它包含 Capability、Artifact、Symbol、Dependency 和 Milestone。Dependency 必须引用平台版本化 `DependencyCatalog` 中允许的顶层坐标、BOM、parent 和构建插件；进入执行前必须通过路径合法性、依赖闭合、契约映射、重复符号、技术栈兼容性、依赖目录和验收覆盖率检查。

`ProjectScaffoldSpec` 是从已通过语义门的 `RequestSpec + PlanIR + ProjectProfile` 确定性编译出的脚手架输入，不接受模型自由文本。它至少冻结 project type、group/artifact、root package、application class、Java/Spring Boot/Maven 版本、Catalog 依赖、配置模板和 scaffold revision。相同 spec 与模板版本必须产生相同文件内容及指纹，便于缓存、审计和 A/B 对比。

`ExecutionManifest` 冻结 run identity、输入指纹、模型与配置、所有必需里程碑和验收分母。状态至少包括 `PENDING/RUNNING/COMPLETED/FAILED/WAIVED/REUSED`，并保证每个 item 恰好处于一个终态集合。恢复时只有输入、PlanIR、工具策略和基线指纹一致的 item 才能复用。

`Diagnostic` 统一承载编译、测试、契约、lint、HTTP 和沙箱错误。除人类可读消息外，还要有稳定 key、严重级别、所有者、关联文件/符号、关联验收项和首次/最近出现版本。

### 7.3 里程碑而不是“全项目一口气”或“逐文件”

推荐的默认里程碑如下：

1. **Bootstrap**：这是由平台负责的零 LLM 里程碑。`ProjectScaffoldService` 依据已验证的 `ProjectScaffoldSpec` 生成构建文件、Java 版本、应用入口、基础配置、标准目录、`.gitignore` 和启动性测试，再由平台对 parent、BOM、依赖、插件和扩展执行 DependencyPreflight，并在固定镜像和已封存缓存上完成最小编译。
2. **Domain/Persistence**：实体、值对象、Repository 和必要迁移，验收是领域单测与持久化切片测试。
3. **API Capability**：按用户能力生成 DTO、Service、Controller 和异常语义，验收是 MockMvc/HTTP 行为测试。
4. **Frontend Integration**：页面与接口绑定，验收是端点映射、关键 DOM/请求合同和可选浏览器 smoke test。
5. **System Acceptance**：跨层集成、隐藏验收、回归、安全和产物报告。

复杂项目可按“创建短链”“查询短链”“跳转并计数”等能力再拆成垂直子里程碑，但每个子里程碑必须携带自身所需的跨层依赖。上面的“验收”描述是各里程碑的默认最小检查映射，不表示每个 checkpoint 都运行全部验证能力。编译门不应机械放在每个尚不完整的文件后，而应放在能形成闭合编译单元的里程碑 checkpoint。

Bootstrap 的实现边界必须明确。正式运行不依赖在线 Spring Initializr，也不要求候选容器安装 Spring CLI；这些工具可以用于开发者维护模板，却不能成为每次生成时的外部网络依赖。平台维护少量版本化本地模板，并直接写入 Windows 的 `ai_generated_projects/<project_run_id>` 权威工作区，容器通过 `/workspace` 立即看到同一批文件，不存在额外 copy-back。

第一版脚手架只生成不含业务语义的稳定骨架：`pom.xml`、`<ApplicationClass>.java`、`application.yml`、标准源码/资源目录、`.gitignore`，以及单独标记为 `BOOTSTRAP_SMOKE` 的最小上下文测试。该测试只证明 Spring 上下文可启动，不计入用户故事覆盖率和最终业务验收。Entity、DTO、Repository、Service、Controller、异常语义、页面和业务测试仍由后续能力里程碑实现；一次 `apply_patch` 可以同时落地多个相关业务文件，不等于退回“一轮写一个文件”。

脚手架只允许在新建或尚未进入业务编辑的工作区幂等渲染。parent、BOM、插件、仓库和 Java/Spring Boot 版本由平台 Profile 所有；Agent 若发现新增依赖需求，应通过 PlanIR/DependencyCatalog 变更请求重新生成受控构建片段，不能任意添加仓库或改版本。业务文件开始写入后，平台不得用整模板覆盖工作区。现有 `ProjectProfileService.applySafeDefaults()` 应拆分：Profile 识别、无歧义 API 兼容修复可以保留，POM/入口/启动测试创建前移到 `ProjectScaffoldService`；旧后置逻辑仅作为迁移期兜底并产生显式诊断。

### 7.4 新的 AgentKernel

AgentKernel 本身应保持简单，只负责：构造当前上下文、请求模型、校验工具调用、执行工具、记录事件、检查预算和决定是否进入验证。业务分解、成功判断和修复进度不应藏在 system prompt 中。

每个阶段可使用两个逻辑角色：Planner 读取 `RequestSpec/PlanIR/DiagnosticSet`，输出本轮变更意图和涉及符号；Editor 只接收变更意图、相关文件和指定编辑协议，负责落地。小修改可以绕过 Planner，避免双模型成本。角色输出都应有 schema，不能靠自由文本约定边界。

预算应分别记录模型轮次、模型可见工具调用、Bash 墙钟时间与输出量、patch 尝试、平台验证次数、输入/输出 token 和金额。达到软阈值先压缩或缩小任务，达到硬阈值则以明确 stop reason 退出，并把可恢复 checkpoint 写入 Manifest。

### 7.5 ContextBuilder 与 RepoMap

上下文拆成四层：

- **Canonical context**：精简的 RequestSpec、AcceptanceSpec 摘要和当前里程碑，不重复全项目蓝图。
- **Structural context**：PlanIR 的依赖子图，以及基于 Java/JS/HTML 语法分析生成的 RepoMap。
- **Working set**：当前要编辑的完整文件和直接依赖文件；其他文件只给符号签名与摘要。
- **FailurePack**：当前仍存在的稳定诊断、最近验证命令、关键输出头尾和上次尝试的净 diff。

采用类似 OpenCodeReview 的 60% 软阈值、80% 硬阈值作为初始实验值，但应由真实模型 context window 换算并在基准中校准。压缩时必须保留完整 assistant/tool 配对、未解决决策和最近一次验证证据；失败时回退原历史，不能产生空摘要覆盖有效上下文。

### 7.6 Shell-first 混合工具内核

目标架构应区分“平台内部能力”和“模型可见工具”。平台可以拥有 Manifest、diff tracker、编译器、测试器、契约验证器、诊断解析器和事件日志，但模型不需要为这些服务分别选择工具。模型可见接口收敛为四个：

```text
bash(command, cwd, timeout)
apply_patch(patch)
submit_checkpoint(milestoneId, summary)
report_blocker(reason)
```

`bash` 运行在当前 run 或 milestone 的持久化候选容器中，固定工作区根目录为 `/workspace`，`cwd` 只能取其内部相对目录。Agent 可以使用 `rg`、`find`、`sed`、`head`、`tail`、`git diff`、Maven 和项目内脚本完成搜索、批量读取、局部诊断和临时验证。一次 Bash 调用可以组合多个相关只读动作，因此不再需要向模型暴露 `list_files`、`search_code`、`read_files`、`get_current_diff` 等细碎 schema。结果必须返回真实 exit code、stdout/stderr 头尾、耗时、资源使用、变更文件和最新写版本；完整输出保存为 artifact。

`apply_patch` 仍作为独立工具保留，因为通过 `sed` 或 heredoc 修改多文件容易产生转义和部分写入问题。它支持 add/delete/update/move、多个 chunk、dry-run 和逐文件结果；文件版本或 blob hash 用于乐观并发控制，但冲突只使对应文件失败，不能恢复整个会话的其他正确文件。建议由宿主 Java 平台把 patch 应用到同一份 Windows 权威工作树，容器会立即看到变化；Bash 和 patch 写操作必须串行。平台在两类工具执行后都通过文件系统快照更新净 diff 和写版本，不能假设只有 patch 会写文件。

`submit_checkpoint` 是 Agent 与平台验收的唯一正式交界。模型可以在 Bash 中运行 `mvn test` 进行自诊断，但这个结果不能直接设置 `compileCurrent/testsCurrent/contractCurrent`，也不能满足最终质量门。提交 checkpoint 后，平台根据 sealed Manifest 在不可被 Agent 修改的配置和验收资产上执行 required compile、tests、contract 和 hidden acceptance；全部通过才接受里程碑，失败则返回结构化 DiagnosticSet。

`report_blocker` 负责以明确 stop reason 结束无法继续的任务并保存最后 checkpoint。RepoMap、FailurePack 和当前里程碑上下文由 ContextBuilder 主动注入，不需要再作为模型工具请求。

这里的“持久化候选容器”只描述容器在多个 Agent 调用之间持续存在，不代表代码只保存在容器里。权威工作区始终是 Windows 本地目录，存储拓扑固定为：

```text
Windows: ai_generated_projects/<project_run_id>
                    ⇅ 单项目读写绑定挂载
Container: /workspace
```

绑定挂载共享的是同一组文件，不存在“容器修改完成后再复制回 Windows”的步骤。Bash 在 `/workspace` 中新增、修改、删除或重命名文件，会立即反映到 Windows；容器删除后代码仍然保留。容器只能看到当前项目目录，禁止挂载 `ai_generated_projects` 父目录、仓库根目录、整个盘符或其他宿主路径。

磁盘工作区应成为候选代码的唯一真实状态，`SoftwareStudioWorkflowData.codes` 只是从磁盘重新构建的派生缓存。每次 Bash 或 patch 后，DiffTracker 都重新扫描真实文件；需要生成上下文或进入旧服务适配器时，再从磁盘加载 `SourceCode`，避免 Bash 新增、删除和移动文件后内存状态失真。

“容器可丢弃”也不等于“挂载目录中的修改可自动丢弃”。平台必须在里程碑开始前记录最后已接受 checkpoint 的快照或内容指纹，写操作串行执行；`submit_checkpoint` 时冻结候选版本，并在该冻结快照上独立验证。通过后封存为新 checkpoint；失败时按策略保留现场继续修复，或精确恢复到最后已接受版本，而不是依赖删除容器实现回滚。

工具预算不能继续只使用一个原始调用数。至少分别记录 `modelTurns`、`visibleToolCalls`、Bash 墙钟时间与输出 token、patch 尝试/成功数、平台内部 `toolOperations` 和正式 `verificationRuns`。一次 `rg` 和一次完整 Maven 集成测试成本完全不同；阶段预算应按 CHEAP/MEDIUM/EXPENSIVE 分类，并为正式 checkpoint 与修复保留额度。

### 7.7 VerificationBroker：把“能构建”与“满足需求”分开

第一版必须把“硬门禁”“验证能力”“诊断”和“质量测量”分开，不能因为平台拥有更多检查能力，就把它们全部串成阻断流水线。运行时只设置三类核心硬门禁：

| 硬门禁类型 | 运行时机 | 阻断条件 | 失败后的动作 |
|---|---|---|---|
| 规划语义门 | 编码前一次 | 必填语义缺失、依赖不闭合、required 用户故事无法映射到可执行计划 | 定向重规划；仍无法解决则 `PLANNING_INVALID` |
| 里程碑 checkpoint 门 | 每个依赖闭合的能力里程碑结束时 | 该里程碑显式绑定的最小 required checks 未通过 | 返回 DiagnosticSet，在本里程碑内修复或按预算停止 |
| 最终行为验收门 | 完整项目结束时一次 | sealed Manifest 中 required Acceptance 未全部通过 | 最终修复；仍失败则项目不声明成功 |

正式支持范围识别可以在规划前拒绝不受支持的项目类型，但它属于产品能力边界，不计入质量门禁。前置基准 Acceptance Suite 主要用于实验判卷；除非其中某项被明确绑定到生产 run 的最终 Manifest，否则也不阻断普通生成流程。

其他机制按性质分为：

| 机制类型 | 典型内容 | 默认是否阻断 |
|---|---|---|
| 验证能力池 | 结构、编译、lint、单元、HTTP、浏览器、隐藏行为测试 | 由当前里程碑的 check policy 决定，不自动全部阻断 |
| 诊断反馈 | DiagnosticSet、lint warning、测试密度提示、可选契约建议 | 否；只有关联 required check 的阻塞级错误才进入门禁结果 |
| 状态与恢复 | ExecutionManifest、checkpoint、DiffTracker、EventJournal | 否；它们保证判定对应正确版本并支持恢复 |
| 实验测量 | 外部基准、调用统计、成本、判卷一致性 | 否；用于判断优化是否有效 |
| 安全与复现 | hardened sandbox、固定镜像、资源限制 | 否；越界或基础设施失败单独终止，不伪装成代码质量失败 |

VerificationBroker 提供六类验证能力，但不在每个 checkpoint 机械执行六遍：

| 层级 | 证明内容 | 不能证明的内容 |
|---|---|---|
| 结构/静态 | 文件、符号、依赖、禁止 placeholder | 业务行为 |
| 编译/lint | 语法、类型、部分框架约束 | 接口语义与数据正确性 |
| 单元测试 | 局部算法与分支 | 跨层集成 |
| 集成/HTTP | 路由、校验、事务、状态码 | 浏览器交互与全部非功能要求 |
| 前端/浏览器 smoke | 关键交互、真实请求 | 深层后端边界条件 |
| 隐藏 Acceptance | 用户故事端到端结果 | 未建模的新需求 |

默认的最小验证矩阵如下，具体项目可以删去不适用项，但不能擅自删掉与 required 用户故事直接关联的检查：

| 里程碑 | 默认 required checks | 默认非阻断或不执行 |
|---|---|---|
| Bootstrap | 构建文件、DependencyCatalog 校验、受控依赖预检、最小生产代码编译 | 业务单测、HTTP、浏览器和隐藏验收不执行 |
| Domain/Persistence | 当前闭合代码编译、领域单测、确有持久化时的切片测试 | lint 风格提示不阻断；不运行前端检查 |
| API Capability | 当前闭合代码编译、相关 MockMvc/HTTP 行为 | 只验证本能力端点，不跑全量浏览器与隐藏套件 |
| Frontend Integration | 前后端端点/载荷映射；存在真实 UI 用户故事时执行最小 browser smoke | 纯 REST 项目不执行浏览器检查 |
| System Acceptance | 完整构建、required 回归、required 外部行为与隐藏验收 | optional 检查只记录结果，unresolved 不进入分母 |

每项 Manifest milestone 只绑定能够证明其完成的最小 required checks，并显式标记 `BLOCKING/DIAGNOSTIC/MEASUREMENT`。`submit_checkpoint` 只是发起所选检查的统一入口，不能把“平台支持哪些检查”解释成“本次必须运行哪些检查”。Bash 内自行执行的 Maven、测试和 lint 只作为诊断证据，不能替代平台验收。生成测试可作为开发辅助，但独立验收测试应由确定性模板、人工维护 fixture 或独立 evaluator 在实现前生成，并与生产代码写权限隔离。

### 7.8 RepairController：诊断集合、责任路由与最佳候选

修复不再接收一整段 Maven 输出，而是接收 `FailurePack`。它先按错误类型路由：编译符号错误给实现 Editor，契约映射错误给契约/接口能力里程碑，测试断言失败给相关能力，基础设施错误直接终止并标记 `INFRA_FAILURE`，不能消耗代码修复预算。

每次修复后比较新旧 DiagnosticSet：阻塞诊断消失且没有同级回归才算有效进步。平台维护当前候选与历史最佳候选；回归时可以恢复最佳 checkpoint，而不是恢复整个会话初始目录。预算按里程碑分配，并为最终系统验收保留独立额度，避免前期修复耗尽全部调用。

### 7.9 EventJournal 与可恢复性

继续使用 JSONL 是正确方向，但事件应成为权威日志而非附属调试信息。每条事件包含 run ID、manifest version、milestone ID、attempt ID、model request ID、tool call ID、基线/结果版本和稳定 stop reason。写入必须追加、flush 后确认，运行总结从事件重建，不能拥有与事件流冲突的第二套状态。

checkpoint 至少记录：候选工作区快照、PlanIR/AcceptanceSpec 指纹、Manifest 状态、未解决 DiagnosticSet、净 diff、模型/工具预算。恢复时先验证身份，再从最后完整 checkpoint 继续；不一致则启动新 run lineage。

### 7.10 Hardened SandboxRunner

推荐为每个 run 或 milestone 建立一个持久化但可丢弃的候选容器，避免每条 Bash 命令重新启动环境，同时不依赖跨命令的隐式 shell 状态；每次调用仍显式传入 `/workspace` 内的 cwd、timeout 和环境白名单。镜像使用固定 digest，并设置非 root 用户、`cap-drop=ALL`、`no-new-privileges`、CPU/内存/PID 限制、只读根文件系统、临时目录 tmpfs、Windows 当前项目目录到 `/workspace` 的唯一读写绑定挂载、默认无网络和强制超时。

当前 `DockerSandboxService` 已经把 Windows 项目目录挂载到 `/app`，但它为每次编译或测试创建短生命周期容器，并通过 `sh -c` 执行固定命令。目标实现保留“宿主项目目录绑定挂载”这一资产，改为会话级 `SandboxSession`，由预装 Bash 的镜像通过 `bash -lc` 执行；若镜像尚未保证 Bash，可暂时把模型工具命名为 `shell`，不能在接口叫 `bash` 时实际静默使用另一套解释器。

Maven 依赖不能实现成三个彼此复制的方案。“预热”是写入时机，“受控缓存”是唯一物理存储，“短时代理”是 cache miss 时的平台下载通道。第一版正式支持范围只有一个固定 JDK/Maven/Spring Boot Profile，因此只维护一份共享缓存，不按项目、run、milestone 或容器复制完整仓库。默认拓扑为：

```text
DependencyCatalog
  -> DependencyResolver（唯一写入者，必要时短时联网）
  -> <SoftwareStudio-5>/.studio-cache/maven/repository
  -> Agent / Verification 容器只读、离线使用
```

缓存根目录由 `studio.sandbox.maven-cache-root` 配置；本项目默认放在 E 盘仓库下的 `.studio-cache/maven` 并加入 Git 忽略，避免 Docker Desktop 的默认数据位置继续占用 C 盘，也不直接读写用户个人 `.m2`。同一物理 artifact store 旁保存按 `JDK + Maven + Spring Boot + catalogVersion` 生成的 cache manifest；扩展 Profile 时复用相同坐标文件，只增加新版本，不创建整仓副本。

`DependencyCatalog` 只约束顶层依赖、parent、BOM、插件、扩展和版本策略，Maven 仍自动解析传递依赖。正式路径禁止 `SNAPSHOT`、`LATEST`、版本范围、模型自定义仓库和未固定版本的插件；第一版目录覆盖 Spring Boot Web、Validation、Data JPA、Thymeleaf、WebSocket、H2、Spring Boot Test 及平台明确批准的少量库。目录外能力进入实验路径或先升级 catalog，不能让 Agent 临时放开互联网。

Bootstrap 生成 `pom.xml` 后，由 `DependencyResolver` 解析完整依赖图，预热 parent、BOM、普通依赖、Maven 插件及其传递项，再执行离线最小编译。缺失依赖必须分类处理，不能统一反馈给模型反复改代码：

| 情况 | 稳定结果 | 处理方式 |
|---|---|---|
| catalog 允许且缓存命中 | `DEPENDENCY_READY` | 候选容器保持断网并继续构建 |
| catalog 允许但缓存未命中 | `DEPENDENCY_CACHE_MISS` | Resolver 独占写锁、短时联网补入同一缓存，然后重试，不消耗 LLM 修复预算 |
| 坐标不存在、版本错误或目录不允许 | `DEPENDENCY_UNRESOLVABLE` / `DEPENDENCY_NOT_ALLOWED` | 返回 Bootstrap/规划诊断，由对应里程碑修正，不进行无限网络重试 |
| 仓库、代理或网络不可用 | `INFRA_DEPENDENCY_UNAVAILABLE` | 作为基础设施失败停止或稍后重试，不消耗代码修复预算 |
| 校验和或缓存清单不一致 | `INFRA_DEPENDENCY_CACHE_CORRUPT` | 隔离精确坐标并由 Resolver 重建，Agent 不得自行覆盖 |

共享缓存必须记录 `cacheBytes/artifactCount/cacheHitRate/cacheMissFetches/evictedBytes`。根据当前个人 Maven 仓库约 2.76 GiB 的实测，第一版先设置 5 GiB 软告警而不是立即硬删除；只有没有活跃 run 时，才按 catalog manifest 和最近使用时间清理不再允许的旧版本、失败下载残留与临时文件。Docker 镜像、共享 Maven 缓存、每个项目的 `target` 和生成源码分别计量，不能把总 Docker 占用误认为依赖缓存。

候选容器原则上使用封存后的只读缓存和 Maven offline 模式。实现前必须用 Maven 3.8.5 做一次只读仓库集成测试；若 Maven 仍需要写锁或元数据，应把预热内容放入只读镜像层，并把必要写入放在容器可丢弃上层或单 run 临时目录，不能因此把共享基础缓存改为 Agent 可写。

构建产物优先写入独立临时卷或受控目录，避免 `target` 噪声污染源码快照。隐藏验收资产位于 Agent 不可写、最好不可见的位置；永不挂载 Docker socket、SSH key、云凭据或其他宿主目录。容器退出后执行可核验清理，但只删除容器与临时卷，不删除 Windows 候选项目或共享依赖缓存。

开放完整 Bash 后，不再假设命令前缀白名单能够提供主要安全性；真正边界是容器能力和挂载范围。若某项任务必须访问网络或宿主资源，应进入独立授权路径，而不是扩大默认 Bash 权限。

## 八、现有代码的保留、重写与删除建议

| 现有模块 | 建议 | 原因与目标去向 |
|---|---|---|
| `SoftwareStudioWorkflowService` | 保留外观，重写内部 | 变成薄 Orchestrator，只驱动状态机，不承载隐式成功逻辑 |
| Requirement/Architecture/Contract Node | 保留职责，重写输出边界 | 进入 `PlanningCompiler + SemanticGate`，使用真 role 和 schema |
| `SlicePlanningService` / `BatchPlanningService` | 合并重构 | 生成真正执行的 `PlanIR/MilestoneGraph`；若不执行就删除冗余产物 |
| `BatchGenerationService` | 替换 | 改为 `MilestoneScheduler`，不再拼一个全项目 `toString()` 目标 |
| `WorkspaceAgentRuntime` | 核心重写 | 拆成 `AgentKernel/ContextBuilder/ModelGateway/SandboxSession`，只编排四个模型可见工具 |
| `WorkspaceToolRegistry` | 替换模型可见工具面 | 收敛为 `BashTool/ApplyPatchTool/SubmitCheckpointTool/ReportBlockerTool`；原编译、测试和契约能力下沉为平台服务 |
| `WorkspaceService` | 保留并强化 | 继续在 `ai_generated_projects/<project_run_id>` 创建 Windows 权威工作区，增加规范化根路径、checkpoint 快照、内容指纹和精确恢复能力 |
| `SoftwareStudioWorkflowData.codes` | 降级为派生缓存 | 不再作为候选代码真相；在 Bash/patch 后按需从磁盘工作区重建，兼容尚未迁移的旧服务 |
| `WorkspaceAgentContextCompactor` | 替换策略 | 从固定轮次改为 token 预算与工作集选择 |
| `WorkspaceAgentStagnationTracker` | 替换 | 使用 DiagnosticSet、净 diff 和验收状态判断进度 |
| `ObservedOpenAiCompatibleChatModel` | 抽象为 ModelGateway | 增加异常分类、幂等重试、模型能力与 request trace |
| `DockerSandboxService` | 保留服务边界，重写运行策略 | 复用现有 Windows 目录绑定挂载，改成会话级候选容器和 `/workspace` 内 Bash，并强化镜像、挂载、身份、资源与网络边界 |
| `ProjectProfileService.applySafeDefaults()` | 拆分并前移 | 保留 Profile 识别和无歧义兼容修复；POM、入口、基础配置与启动测试的创建移到 Agent 执行前，后置兜底不能再掩盖 Bootstrap 失败 |
| 新增 `ProjectScaffoldSpec/ProjectScaffoldService` | 平台内部服务 | 从通过语义门的计划确定性生成 Spring Boot Maven 骨架，记录模板 revision 与文件指纹；正式运行不依赖在线 Initializr，不向模型暴露脚手架工具 |
| 新增 `DependencyCatalog/DependencyResolver` | 平台内部服务 | 固定正式支持依赖与版本；维护 E 盘单一共享缓存、DependencyPreflight、短时受控下载、校验和与稳定失败分类，不向模型暴露工具 |
| `ToolDrivenRepairService` 等 | 重构 | 改为诊断路由、最佳候选、分层预算与最终预留 |
| `BenchmarkQualityEvaluator` | 重写质量门 | 引入外部隐藏行为 oracle，结构门只作前置条件 |
| `RunJournalService` 与工具 JSONL | 保留并升级 | 发展成可重放 EventJournal 和 Manifest 快照 |
| 写版本与“验证是否为最新修改”逻辑 | 保留 | 这是防止旧验证结果冒充当前结果的正确机制 |
| 真实 Maven 编译/测试结果模型 | 保留 | 作为 VerificationBroker 的底层适配器 |

这意味着不是完整重写产品：Controller/API、前端展示、工作区创建、结果 DTO 和已有确定性验证代码可逐步迁移。真正需要替换的是从“规划结果进入执行”到“最终成功判定”的控制面。

## 九、分阶段实施方案

本方案不把所有组件拆成十个实施阶段。RepoMap、Manifest、DiagnosticSet、Sandbox 等是技术组件，不应各自成为项目里程碑。实施上采用“一个前置基线 + 四个改造阶段”，每个阶段只对应一个核心效果目标：先确认真实质量，再依次解决目标错误、任务过大、执行低效和修复失灵。

可以用下面的概念关系理解各阶段作用。它不是数学公式，而是用于判断改造因果位置：

```text
有效生成质量
  ≈ 目标正确率
  × 实现完成率
  × 修复转化率
  × 验收可信度
```

| 实施单元 | 核心问题 | 主要建设内容 | 对代码质量的作用 |
|---|---|---|---|
| 前置基线 | 不知道优化是否真实有效 | 固定实验条件、外部行为测试、失败分类 | 不直接提升，负责测量 |
| 阶段一：目标正确 | 错误规划进入编码 | 语义门、RequestSpec、PlanIR、危险默认值清理 | 直接提升 |
| 阶段二：任务可完成 | 从空目录写固定脚手架、全项目任务过大、没有真实进度单位 | 确定性 Bootstrap、能力里程碑、ExecutionManifest、checkpoint | 脚手架与里程碑直接提升，Manifest 间接支撑 |
| 阶段三：执行高效 | token 和轮次耗在工具选择、重复读取、冲突与无关上下文 | Shell-first 混合工具、RepoMap、ContextBuilder、patch、ModelGateway | 直接提升，预计收益较大 |
| 阶段四：验证修复闭环 | 能编译但行为错误，修复反馈粗糙 | 独立验收、DiagnosticSet、RepairController、最佳候选 | 修复直接提升；验收与沙箱负责证明和复现 |

这些阶段不能理解为持续叠加门禁。第一版运行时固定为“编码前规划语义门 → 开发中里程碑最小验证 → 最终 required 行为验收”三类硬门；基准判卷、Manifest、快照、日志、沙箱和大部分诊断不会形成额外质量关卡。六类验证能力按里程碑选择，不允许每次 checkpoint 默认运行全量套件。

### 9.1 前置基线：先知道“真实效果”是什么

#### 当前问题与证据

当前基准质量门可能被一个 `contextLoads()` 同时满足“存在测试”“存在 SpringBootTest”和“测试绿色”中的多项要求；契约又主要来自生成链上游，可能形成自己出题、自己作答。较新的历史报告还对应旧 dirty 提交并在工作流异常后没有形成可验证项目。因此，当前数据不足以判断任意改造究竟提高了业务质量，还是只提高了结构门通过率。

#### 建设内容

这一单元只建设最小可用判卷基线，不在一开始实现庞大的通用评测平台：

- 先选择短链接与在线投票两个代表案例，为每条关键用户故事建立外部行为测试；
- 固定 Git commit、模型及修订、temperature、总 token/费用预算、Docker 镜像、案例版本和工具策略；
- 报告必须保存模型调用、停止原因、项目路径、验证摘要、外部验收结果和失败类别；
- 补充阿里百炼平台的缓存命中率观测，使任务报告能够统计百炼返回的缓存命中 token；
- 将 `PLATFORM_FAILURE`、`INFRA_FAILURE` 与 `GENERATED_CODE_FAILURE` 分开，平台异常不能记成模型代码质量失败，也不能记成成功；
- `contextLoads()` 继续作为启动性检查，但不再代表业务测试充分。

外部验收应测试可观察行为，不绑定某个具体类名或内部实现。例如短链接案例检查创建、复用、非法 URL、跳转计数和过期语义，而不是强制要求某个 Repository 方法必须采用指定名称。

#### 作用机制和效果边界

前置基线不会让模型写出更好的代码，甚至可能使报告通过率下降，因为它会暴露过去未被检查的问题。它的价值是固定后续所有阶段的“尺子”：如果只增加了日志和门禁，却没有提高同一套外部行为测试的通过率，就不能宣称生成效果提升。

#### 指标、风险和退出条件

本阶段关注报告完备率、相同产物重复判卷的一致性、平台失败分类准确性和用户故事覆盖率，不以生成通过率上升为目标。主要风险是测试过度绑定实现或把模糊需求写成武断断言，因此 Acceptance Suite 必须评审需求来源并区分 required、optional 和 unresolved；其中 unresolved 不阻塞等待用户，而是不得进入本轮 required 判卷分母。

退出条件是两个代表案例能够在固定环境中稳定判卷，任意一次报告都能回答“输入和版本是什么、为什么停止、哪些用户故事通过”。随后再扩展到现有 6 个案例。

### 9.2 阶段一：保证模型收到正确目标

#### 当前问题与证据

短链接案例已经出现明确污染链：描述要求 POST 创建短链接，结构化前端调用却因空字段被自动补为 `GET /`，后续代码再增加根路径请求与控制器以满足错误契约。另有 `rootPackage=null`、空 owner 和空视图关系等结构合法但语义不完整的规划结果。此时更强的编码模型只会更准确地实现错误目标。

#### 建设内容与代码范围

本阶段集中修改规划输出进入编码前的边界：

- 删除 `ApiEndpointContract`、`FrontendCallContract` 等对象把必填语义静默补成合法值的逻辑；缺失值保持缺失并形成诊断；
- 引入规范化 `RequestSpec`，明确用户故事、技术约束、非功能要求、显式生成假设和仍未解决的问题；第一版不增加用户追问或等待恢复接口；
- 将架构、契约和切片结果编译成 `PlanIR`，统一表达 Capability、Artifact、Symbol、Dependency 和 Milestone 候选；
- 建立版本化 `DependencyCatalog`，固定正式 Spring Boot Profile 可使用的顶层依赖、parent、BOM、插件与版本策略，禁止 `SNAPSHOT`、`LATEST`、版本范围和模型自定义仓库；
- 增加 `PlanningValidationService`，检查 root package/main class、路径/package、端点 owner、前后端调用、视图返回关系、依赖闭合、DependencyCatalog 合规和用户故事验收覆盖；
- 规划失败时把带 JSON path 和规则 ID 的 findings 定向反馈给对应规划 Agent，最多重规划一次；仍失败则以 `PLANNING_INVALID` 在编码前结束，不能让 Coder 猜测，也不能等待用户输入；
- `BatchGenerationService` 不再直接接收未经语义门确认的对象 `toString()`。

`AcceptanceSpec` 在此阶段与 `RequestSpec` 建立稳定映射，但外部隐藏测试仍由前置基线维护。前者告诉系统“需要证明什么”，后者负责独立判卷，二者不能由同一份生成代码反向推导。

#### 为什么会提高效果

这一阶段直接提高“目标正确率”，同时减少无效返工：

```text
缺失或冲突的规划
  -> 编码前形成确定性诊断
  -> 定向重规划或失败退出
  -> Coder 只接收经过语义确认的目标
  -> 不再花大量 token 实现错误契约
```

它可能使表面的“生成完成率”短期下降，因为过去带病进入编码的任务会被提前拦截。判断它是否有效，应看错误规划进入 Coder 的比例和独立行为通过率，而不是只看生成目录数量。

#### 指标、风险和退出条件

硬性不变量是必填字段静默默认率为 0、通过语义门的计划不包含未解释的 `rootPackage=null`，以及 required frontend call 必须能映射到 endpoint。效果指标包括首次规划通过率、定向重规划成功率、进入 Coder 后发现的规划类缺陷数量、因错误契约触发的修复次数和外部行为通过率。

主要风险是规则过严，误拒绝合理架构，或者把启发式偏好写成必需约束。每条规则必须分成 BLOCK/WARN，并提供稳定 rule ID、正反例和关闭理由。退出条件是两个代表案例不再出现已知语义污染，且旧生成器在同预算下没有因为新增无关约束而显著降低外部行为结果。

### 9.3 阶段二：把完整项目变成模型能够完成的任务

#### 当前问题与证据

当前切片和批次只用于上下文、预算和报告，真正执行仍由一个 IMPLEMENT 会话实现完整项目。短链接和投票目标分别约 17,686 与 26,835 字符；两个项目的 IMPLEMENT、FRONTEND_INTEGRATION、TEST、REPAIR_CONTRACT 会话全部以 `MODEL_TURN_LIMIT` 结束。投票项目初始会话修改了 24 个文件，却没有在任何 Agent 会话中到达编译或测试。

#### 建设内容与代码范围

本阶段将现有切片/批次重构成真正执行的 `MilestoneGraph`，并用 `ExecutionManifest` 管理状态：

- `SlicePlanningService` 与 `BatchPlanningService` 合并为 PlanningCompiler 的一部分，输出依赖闭合的能力里程碑；
- 新增 `ProjectScaffoldSpec`，只从已通过阶段一语义门的 root package、application class、Profile、Catalog 依赖和版本策略编译，缺字段时不得用模板猜测；
- 新增 `ProjectScaffoldService`，在 Agent 启动前向 Windows 权威工作区确定性写入 `pom.xml`、应用入口、基础配置、目录、`.gitignore` 与 `BOOTSTRAP_SMOKE` 测试，并记录 scaffold revision 和文件指纹；
- `BatchGenerationService` 替换为 `MilestoneScheduler`，一次只向 Agent 提交当前里程碑相关需求、产物和验收项；
- 默认里程碑包括平台执行的 Bootstrap，以及 Agent 执行的 Domain/Persistence、API Capability、Frontend Integration 和 System Acceptance；复杂功能再按能力拆分；
- Bootstrap 生成后立即调用平台 `DependencyResolver`，完成依赖目录校验、cache preflight 和离线最小编译；允许依赖的首次下载属于平台操作，不占用 Agent 轮次；Bootstrap 通过后，Agent 才从第一个业务里程碑开始；
- parent、BOM、插件、仓库和框架版本由平台所有；新增依赖必须经过 PlanIR/DependencyCatalog，禁止 Agent 直接把任意坐标或仓库写入 POM；
- `ExecutionManifest` 在执行前 seal 全部必需里程碑和最小 required checks，并为检查标记 `BLOCKING/DIAGNOSTIC/MEASUREMENT`，记录 `PENDING/RUNNING/COMPLETED/FAILED/WAIVED/REUSED`；
- 每个里程碑结束时保存候选工作区 checkpoint、净 diff、最新验证和未解决诊断；
- `SoftwareStudioWorkflowService` 变成薄 Orchestrator，只依据 Manifest 状态推进；
- 编译门只放在依赖闭合的 checkpoint；单文件中间态、lint 风格告警和与当前能力无关的测试不阻断里程碑。

Manifest 和里程碑的作用需要分开理解。Manifest 本身不会提高模型编码能力，它负责让完成状态、失败隔离和恢复可信；真正直接提高效果的是里程碑调度，因为它缩小了单次模型任务和失败范围。

本阶段内部不再拆成新的项目管理阶段，但交付顺序应明确：第一份可运行交付先完成“确定性 Bootstrap + DependencyPreflight + 一个业务能力里程碑”，验证 Agent 是否能从可编译骨架更快进入业务实现；随后再推广完整 MilestoneGraph、恢复与复用。它不适合早于阶段一的最小语义校验，因为错误的 root package、入口类或依赖若进入 spec，平台只会更稳定地生成错误骨架；但它也不需要等待阶段三的 Shell-first 内核、完整 RepoMap 或阶段四 RepairController，是执行侧最适合优先落地的改造。

#### 为什么会提高效果

```text
已通过语义门的 ProjectScaffoldSpec
  -> 平台零模型轮次生成并编译稳定骨架
  -> Agent 不再消耗轮次选择版本、搭目录和修基础启动错误
  -> 一个 20 多文件的全项目任务被拆成多个依赖闭合的业务能力里程碑
  -> 每轮只维护相关需求、符号和文件
  -> 更早到达真实编译/行为测试
  -> 错误定位在当前能力范围内
  -> 已通过能力通过 checkpoint 保留
```

它主要提高“实现完成率”：确定性脚手架先消除固定、重复且高频出错的工程初始化任务，里程碑调度再缩小业务实现范围。模型不必在一个会话里同时选择框架版本、搭建工程结构并记住所有跨层关系，修复也不必扫描整个项目。中断后可从最后验收 checkpoint 继续，而不是重复实现全部文件。

#### 指标、风险和退出条件

关键效果指标是脚手架确定性生成成功率、Bootstrap 冷/热缓存最小编译通过率、Bootstrap 相关生成缺陷率、Agent 首次业务编辑前模型轮次、相对旧流程节省的模型轮次/token、首次业务 checkpoint 时间、`MODEL_TURN_LIMIT` 占比、单个业务里程碑完成率、已完成里程碑回归率和恢复后的重复工作量。Manifest 状态分区 100% 合法属于系统不变量，不应被误当作质量提升指标；脚手架文件数量也不是效果指标。

脚手架侧的主要风险是模板数量失控、版本过期、错误 spec 被确定性放大，以及重渲染覆盖 Agent 业务修改；通过第一版单一正式 Profile、模板 revision、语义门、仅在新工作区渲染和文件所有权规则控制。调度侧的最大风险是拆得过细或过粗：逐文件里程碑会制造不可编译中间态，过大的“后端全部实现”又会回到原问题。退出条件是两个代表案例的 Bootstrap 均无需 Agent 修改框架骨架即可通过最小编译，大多数业务里程碑能在硬预算前至少完成一次与最新快照对应的真实验证，且外部行为结果不低于基线。

### 9.4 阶段三：提高每个模型轮次的有效工作量

#### 当前问题与证据

当前模型可见工具有 list/search/read/edit/diff/compile/test/contract/complete/blocker 十种。`read_files` 和 `apply_edits` 已支持数组，所以问题不只是“一文件一调用”：短链接平均每次读取 3.86 个文件、每次编辑 2.47 个文件；投票系统分别是 4.61 和 2.42。真正的问题是读取仍返回整文件，创建与修改共用 SHA/唯一 `oldText` 协议，多文件写入失败边界过大，并且模型需要自己选择多个正式验证工具。

两个真实项目均出现 60 个模型轮次对应 60 次工具调用，并各发生 40 次上下文压缩。配置虽然给 IMPLEMENT 24 个模型轮次和 72 次工具调用，但请求设置 `parallel_tool_calls=false`，真实轨迹每轮只返回一次调用，所以 72 次上限从未生效，实际有效上限就是 24 次模型轮次。短链接 15 次 `apply_edits` 失败 4 次，失败率 26.7%；投票系统 12 次失败 5 次，失败率 41.7%。两者分别读取 81/143 个路径项，但唯一文件只有 20/27 个；重复读取并非全部无效，但仍显示 SHA 刷新和上下文丢失造成明显反复。投票系统四个 Agent 会话内甚至没有执行一次正式编译、测试或契约验证。

当前压缩还永久保留 17K/26K 字符的原始目标，却丢弃早期动态决策。结果不是工具数量不足，而是每个模型轮次产生的有效进展太少。

#### 建设内容与代码范围

本阶段不再扩展更多细碎工具，而是把平台内部能力与模型工具分离：

```text
模型可见：bash + apply_patch + submit_checkpoint + report_blocker

平台内部：ContextBuilder、RepoMap、DiffTracker、ExecutionManifest、
          Compile/Test/Contract/Acceptance、DiagnosticParser、EventJournal
```

具体改造如下：

- `ContextBuilder` 只提供当前里程碑、相关 Acceptance 摘要、RepoMap、当前工作集和 FailurePack；RepoMap 在空仓库阶段使用 PlanIR 预期符号图，文件生成后切换到真实定义与引用图；
- 上下文按 token 软/硬阈值保留完整 assistant/tool 轮次，不再按固定第 6 轮机械压缩；
- `BashTool` 在当前 milestone 的持久化 hardened container 中执行完整 Bash，固定项目根目录，允许模型使用 `rg/find/sed/git diff/mvn` 等完成组合搜索、读取和自诊断；
- `WorkspaceService` 先在 Windows 的 `ai_generated_projects/<project_run_id>` 创建本轮权威工作区，`SandboxSession` 只把该目录读写绑定到 `/workspace`；Bash 修改立即持久化，不设计任务结束后的 copy-back；
- Bash 结果返回真实 exit code、stdout/stderr 头尾、耗时、资源使用、变更文件和写版本，完整输出另存 artifact；
- `ApplyPatchTool` 由宿主平台在同一 Windows 工作树执行，支持 add/delete/update/move、多个 chunk、dry-run 和逐文件结果；它与 Bash 写入串行，冲突只影响对应文件，两类写入都由 DiffTracker 扫描发现；
- 磁盘文件成为候选代码真相，`data.codes` 降级为按需重建的兼容缓存；里程碑开始前保存已接受快照，提交时冻结版本，验证失败时保留现场修复或恢复最后 checkpoint；
- `SubmitCheckpointTool` 根据 sealed Manifest 触发不可被模型篡改的 compile、tests、contract 和 hidden acceptance；Bash 中的 `mvn test` 只用于诊断，不能改变正式验收状态；
- `DependencyResolver` 维护 E 盘唯一共享 Maven cache；Agent 容器只读、离线使用，cache miss 由平台短时联网补齐，禁止每个 run 复制完整仓库；
- `ModelGateway` 保留真实 system/user/tool role，只对限流、临时连接和可证明幂等的错误指数退避；Architect/Editor 分工只用于跨文件复杂改动，小修改走单 Editor 快路径；
- `WorkspaceAgentRuntime` 不再暴露旧的十种工具，而是编排 Bash/Patch/Checkpoint/Blocker 与平台事件；旧 `compile_main/run_tests/validate_contract` 下沉为 VerificationBroker 适配器。

开放完整 Bash 的前提是先完成候选容器的非 root、默认断网、只读根文件系统、项目唯一读写挂载、资源/PID/超时限制和无宿主凭据等边界。允许 `bash -lc` 后，安全不能依赖命令前缀白名单；任何网络或宿主访问都走独立授权路径。

#### 为什么会提高效果

这一阶段直接把模型预算从“选择和操作细碎工具”转移到“实现和验证”：

```text
相关上下文替代全项目长目标
  -> 降低无关 token 和跨文件混淆

一次 Bash 调用组合搜索、读取和局部诊断
  -> 即使一轮只有一个工具调用，也能完成多个相关动作

结构化 patch + 局部冲突
  -> 减少 STALE_FILE、重复读取和整轮编辑失败

一次 submit_checkpoint 触发全部 required checks
  -> 减少 compile/test/contract/complete 的模型往返

平台保留有界真实输出、净 diff 和 FailurePack
  -> Bash 的灵活性不会牺牲可追踪性和正式验收

释放出的轮次
  -> 更早用于编译、测试和针对性修复
```

这是预计直接收益最大的阶段之一，但效果必须在阶段二的里程碑边界内测量；否则任务规模变化与工具变化混在一起，无法知道收益来源。

#### 指标、风险和退出条件

调用统计至少分为 `modelTurns`、模型可见 `visibleToolCalls`、Bash 内部实际操作与墙钟时间、patch 尝试/成功数，以及平台 `verificationRuns`。不能把一次 `rg` 和一次全量 Maven 测试都简单记作“一次工具调用”。核心效果指标包括有效进展轮次占比、首次 checkpoint 前轮次、Bash 输出 token、patch 首次成功率、重复读取率、每个里程碑正式验证到达率和单位成功任务成本；依赖侧另记 cache 大小、命中率、miss 下载次数、首次 Bootstrap 预热时间和依赖基础设施失败率。

主要风险是 Bash 修改范围失控、产生超长噪声输出、运行不可复现命令，或尝试修改测试来制造绿色结果；还包括 RepoMap 漏掉动态调用、patch parser 错误、压缩摘要丢失决策，以及 Maven cache 无界增长、并发写损坏或缺依赖被误判成代码错误。必须依靠 hardened sandbox、完整净 diff、隐藏验收隔离、输出有界、checkpoint、单写者 DependencyResolver 和正式 parser 测试控制风险。退出条件不是“Bash 能运行”，而是同模型、同里程碑、同预算下，首次 checkpoint 更早、patch/协议失败下降、外部行为通过率不退化，缓存占用保持在可解释预算内，并且没有宿主越界与验收绕过。

### 9.5 阶段四：建立能够真正修好的验证—诊断—修复闭环

#### 当前问题与证据

当前独立质量门偏结构化，生成项目可能只有 `contextLoads()`；契约可能来自错误规划。修复侧主要比较阶段得分、错误数量和整段日志指纹，无法稳定表达“解决一个阻塞错误但新增两个次要问题”是否进步，也可能因一次 `NO_PROGRESS` 或回归较早放弃。写入失败恢复会话初始基线还可能丢失此前有效修改。

#### 建设内容与代码范围

本阶段完成三个彼此配合、但效果类型不同的部分：

1. `VerificationBroker` 统一提供编译、lint、单元、集成、HTTP、前端 smoke 和外部 Acceptance 能力，但严格根据 Manifest 的最小验证矩阵选择执行，不在每次 checkpoint 默认跑全量套件。编码 Agent 可以看到公开契约，但不能修改或完整读取隐藏判卷断言。
2. `DiagnosticSet` 将工具输出解析为稳定诊断，key 至少包含 stage、tool、file、symbol、normalized location 和 error code，并关联 owner、severity 与 acceptance ID。
3. `RepairController` 根据诊断 owner 构造最小 FailurePack，保存当前候选和历史最佳 checkpoint，修复后重新验证并比较诊断集合与行为结果。

修复流程变为：

```text
冻结候选快照
  -> 执行 required checks
  -> 形成 DiagnosticSet
  -> 路由给相关能力和 Editor
  -> 应用候选 patch
  -> 对新快照重新验证
  -> 有真实进步则提交 checkpoint
  -> 回归则恢复历史最佳候选
```

基础设施错误直接标记为 `INFRA_FAILURE`，不消耗代码修复预算；其中依赖网络不可用和缓存损坏分别保留 `INFRA_DEPENDENCY_UNAVAILABLE`、`INFRA_DEPENDENCY_CACHE_CORRUPT` 子类，不能与模型生成了不存在坐标的 `DEPENDENCY_UNRESOLVABLE` 混在一起。修复预算按里程碑和最终系统验收分层，最终验收保留独立额度。Docker 在本阶段完成非 root、默认断网、只读根文件系统、资源/PID 限制、`cap-drop ALL`、no-new-privileges 和固定镜像等发布级加固。

检查结果必须携带 policy：`BLOCKING` 失败才阻止 checkpoint；`DIAGNOSTIC` 只进入 FailurePack；`MEASUREMENT` 只进入报告。required、optional 和 unresolved 由 AcceptanceSpec 在编码前确定，不能因为某个验证器恰好存在，就在运行中把 optional 动态升级成 required。

#### 为什么会提高效果，以及哪些部分不直接提高

DiagnosticSet 与 RepairController 会直接提高“修复转化率”：模型收到的是当前仍存在的稳定问题、相关文件和最近 diff，而不是整段噪声日志；平台能够保留有效候选并避免重复修同一根因。

独立验收不会直接帮助模型编码，它负责揭示真实行为质量，可能让表面通过率下降。沙箱硬化也不会提高模型智力，主要减少环境噪声并保证复现。只有把三者区分，才能避免把“门禁更严”误写成“生成更好”。

#### 指标、风险和退出条件

直接效果指标包括修复后阻塞诊断消除率、一次修复转绿率、同一诊断重复率、新增同级回归率、恢复最佳候选次数和隐藏行为测试通过率。辅助指标包括判卷一致性、基础设施失败误归类率和相同镜像重复运行一致性。

风险是隐藏验收与需求不一致、诊断归并过度、最佳候选选择只看错误数而忽略行为，以及过早增加大量测试导致成本失控。候选排序必须优先 required behavior，再比较阻塞诊断和成本；同时监控单 checkpoint 验证耗时、重复检查比例和误阻断率，防止 VerificationBroker 演变成门禁堆叠器。退出条件是每次修复都能说明消除了哪些稳定诊断、引入了哪些回归；最终成功完全由 sealed Manifest 和 required Acceptance 决定，并能在干净环境复现。

### 9.6 模型路由与性能优化作为持续实验

模型能力当然可能是最终上限，但不再把它包装成一个新的框架重构阶段。完成前四阶段后，在完全相同的需求、验收、工具和预算条件下，持续比较 planner/editor 模型、是否采用双模型角色、prompt cache、里程碑大小和 token 阈值。

如果更强模型只增加成本，却没有提高独立行为通过率或降低修复次数，就不保留；如果一个较便宜模型能处理摘要和简单分类，则只在该职责上路由。这样模型选择成为可重复的优化实验，而不是掩盖控制面缺陷的替代方案。

## 十、基准验证与消融实验

### 10.1 指标必须对应阶段的作用

主质量指标始终是“独立行为验收通过率”和“完整工作流成功率”。其他指标用于解释为什么变化，不能替代主指标。模型请求全部返回 200、执行过 Docker、产生过 diff、编译绿色或 `platformSuccess=true` 都不能单独证明业务质量。

| 实施单元 | 预期变化 | 用于判断的核心指标 | 不应被误当成效果的指标 |
|---|---|---|---|
| 前置基线 | 判卷可信、失败可归因 | 报告完备率、行为覆盖、判卷一致性 | 通过率必须上升 |
| 阶段一 | 错误目标不再进入编码 | 语义污染率、重规划成功率、行为通过率 | 规划门数量 |
| 阶段二 | 零轮次获得稳定骨架，并更早完成局部业务能力 | Bootstrap 缺陷率、节省轮次/token、首次业务 checkpoint、turn-limit、里程碑完成率、行为通过率 | 脚手架文件数或 Manifest 记录条数 |
| 阶段三 | 相同预算完成更多有效工作 | 有效进展轮次、首次 checkpoint、Bash 时间/输出、patch 成功率、token/成功任务、依赖缓存命中率与占用 | 模型可见工具数量或缓存文件数本身 |
| 阶段四 | 失败更容易被修好且结果可信 | 修复转化率、回归率、重复诊断率、隐藏验收通过率 | 测试文件数量 |
| 模型实验 | 同等条件下能力或性价比提高 | 同预算行为通过率、修复次数、成本 | 模型参数规模或单次调用成功 |

### 10.2 分阶段 A/B，而不是一次完成全部重构再比较

建议先对短链接和投票系统各运行 1 次 smoke，确认新路径能形成完整报告；正式判断使用现有 6 个案例，每个变体至少 5 次。所有变体保持模型修订、temperature、总 token/费用上限、镜像、案例和外部验收不变。

第一版沿用串行运行策略：不同生成 run、基准案例和重复轮次在同一服务实例中依次执行，不新增并发 benchmark scheduler，也不同时提交多个 `/benchmarks/run`。这样可以避免进程级模型指标重置、候选目录、Docker 资源和供应商配额互相污染。这里的串行只约束 run 之间的调度，不禁止一次 Bash 调用组合多个只读动作，也不排除 VerificationBroker 在单个冻结快照上执行彼此独立的受控检查；跨 run 并发等基线稳定后再单独优化。

| 变体 | 唯一新增能力 | 要验证的因果假设 |
|---|---|---|
| B0 | 当前基线 | 得到真实失败分布和成本 |
| B1 | 阶段一语义门 | 正确目标减少错误实现与无效修复 |
| B2a | 阶段二确定性 Bootstrap，其余 Agent/预算不变 | 从模型任务中移除固定脚手架是否减少基础缺陷和轮次，并更早进入业务实现 |
| B2b | 在 B2a 上增加能力里程碑与 Manifest | 进一步缩小业务任务是否使 Agent 更早到达真实行为验证 |
| B3 | 阶段三 Shell-first 混合内核 | Bash/Patch/Checkpoint 在相同轮次内完成更多有效探索、编辑和正式验证 |
| B4 | 阶段四诊断修复闭环 | 稳定诊断和最佳候选提高修复转绿率 |
| M1/M2 | 在 B4 上只改变模型或路由 | 测量模型本身的增益与成本 |

`B2a/B2b` 只是为了隔离脚手架和里程碑的效果，不是把实施方案增加成两个新阶段。B2a 必须使用相同模型、业务目标、总预算和外部验收，只比较“Agent 从空目录创建全工程”与“平台预置确定性骨架后 Agent 实现业务”。阶段三内部可做两个短期微消融：先比较旧十工具与 `Bash + apply_patch` 的探索/编辑效率，再加入 `submit_checkpoint` 测量正式验证往返是否下降；RepoMap 可作为同阶段的上下文子实验。主交付仍视为一个阶段，避免把项目管理拆成十个里程碑。每个变体都保存原始轨迹、Bash 命令及 exit code、候选项目、Manifest、净 diff、诊断和外部验收，报告逐案例结果与不确定性，不能用一次随机成功代表整体提升。

### 10.3 保留、调整或撤回的判定

每个阶段都是一个可证伪假设，而不是因为架构更完整就必须保留：

- 前置基线只需证明判卷稳定，不要求生成通过率上升；
- 阶段一若只增加规划失败、未减少进入编码后的语义缺陷，应调整规则和重规划策略；
- 阶段二先分别判定两个假设：若 B2a 未减少 Bootstrap 缺陷、模型轮次/token 或首次业务编辑时间，应简化脚手架；若 B2b 的 Manifest 正常但首次行为验证到达率、turn-limit 和行为结果不变，说明里程碑划分没有产生效果；
- 阶段三若换成四个核心工具后，首次 checkpoint、有效进展轮次、patch 成功率或 token 没有改善，或者出现宿主越界/验收绕过，应调整或撤回 Shell-first 实现；
- 阶段四若修复次数增加但诊断消除率与行为通过率不升，说明 FailurePack、路由或候选选择无效；
- 模型实验只在同预算或明确的成本—质量曲线更优时保留。

硬性系统不变量仍包括：必填语义静默默认率为 0、Manifest 状态分区合法、成功任务的 required acceptance 全部通过、平台失败不消耗代码修复预算。至于工具错误率、turn-limit 和里程碑验证到达率，不在缺少可信基线时预设虚假的精确目标；先测 B0，再为各阶段设定相对改善幅度和停止线。

## 十一、哪些内容可以“照搬”，哪些只能借鉴

### 11.1 接近可直接移植的机制

- OpenCodeReview 的 Manifest 状态不变量、稳定 identity/fingerprint、显式 stop reason 和 token 阈值压缩算法，可按 Java 数据模型重写。
- Codex CLI 的 apply-patch 语法、工具并发元数据、head/tail buffer、追加式 recorder 和精确净 diff，可裁剪成独立 Java 组件。
- mini-SWE-agent 的 Model/Environment 接口、Shell-first 主循环、可重试异常分类和每步 trajectory 持久化，可直接作为接口设计参考。
- Aider 的 architect/editor 分工、编辑 dry-run、失败块回灌和 lint/test reflection 次数上限，可融入 AgentKernel。

### 11.2 必须按本项目改造的机制

- OpenCodeReview 的 per-file 任务要改成 capability milestone；全栈生成不能按文件证明完成。
- Aider RepoMap 在空仓库阶段要由 PlanIR 预期符号图启动，不能依赖已有代码图。
- Aider 的 Git 自动 commit/undo 要改成平台候选快照，不让模型拥有版本历史策略权。
- Codex 的人类审批流在候选容器内部可简化为固定挂载与能力边界，但网络、宿主写入或高风险外部动作仍需单独授权。
- mini-SWE-agent 的宿主 Shell 要改为 hardened candidate container；SWE-bench oracle 思想要改造成自有绿地项目隐藏验收集，不能直接复用其任务数据。
- 确定性 `ProjectScaffoldService` 不是从某一个参考项目直接复制的组件，而是针对本项目“绿地生成”的适配：沿用 mini-SWE-agent/Codex CLI 把 Agent 循环集中在真实探索与编辑上的原则，结合现有 `ProjectProfileService` 和本地 POM 模板，把固定工程初始化下沉为平台能力。四个参考项目大多从已有仓库开始，不能直接解决空工作区 Bootstrap。

### 11.3 明确不应照搬的内容

- 不开放宿主机 unrestricted shell；允许的是无宿主凭据、默认断网、资源受限且可丢弃的候选容器内完整 Bash。
- 不允许 Bash 中自行运行的命令或文本输出直接满足 `submit_checkpoint`；正式验收必须由平台执行。
- 不照搬 OpenCodeReview 的逐文件并发实现生产功能。
- 不移植 Codex CLI 的全部通用产品架构；只取和本项目失败模式直接相关的内核组件。
- 不增加更多自由文本 Agent 角色来代替确定性语义门；Agent 数量增加会扩大错误传播面。
- 不先扩大模型轮次和修复次数。当前失败首先是目标与控制面错误，增加预算只会更昂贵地执行错误计划。

## 十二、许可证与代码复用边界

本地快照中 OpenCodeReview、Codex CLI、Aider 使用 Apache License 2.0，mini-SWE-agent 使用 MIT License。设计思想可以重新实现；若复制具体源文件、函数或较大代码片段，应记录来源仓库、文件和提交，保留版权与许可证文本，并检查目标仓库的 NOTICE、文件级声明及所引入依赖的许可证。Apache-2.0 代码的分发还涉及 NOTICE 与修改说明。这里是工程合规建议，不替代正式法律意见。

## 十三、最终建议

推荐路线仍是“控制面重构，执行资产复用”，但第一版只对 Java Spring Boot Maven Web/REST 项目承担正式质量保证；纯 Java Maven 和原生 Java 作为实验路径，不进入同一成功统计。项目管理上只设置一个前置基线和四个改造阶段，不把每个技术组件都变成独立里程碑：

```text
可信基线
  -> 目标正确
  -> 任务可完成
  -> 执行高效
  -> 验证修复闭环
  -> 持续模型实验
```

如果资源有限，第一轮应完成前置基线、阶段一的最小必要语义字段，然后优先在短链接/投票案例上落地阶段二的 `ProjectScaffoldSpec + ProjectScaffoldService + DependencyPreflight`，再接一个真实业务能力里程碑。这样可以依次验证三个关键假设：错误规划是否被阻断，移除模型负责的固定脚手架后是否更快进入业务实现，以及缩小业务任务后是否更早到达真实行为验证。不要等待完整 RepoMap、Shell-first 通用命令、复杂恢复或多模型路由才开始做脚手架，也不要在最小语义字段尚未可靠时直接渲染模板。

阶段三的正式方向是 Shell-first 混合 Agent，而不是继续增加专用工具：

```text
模型可见：bash + apply_patch + submit_checkpoint + report_blocker
平台内部：Manifest + Diff + Verification + Diagnostic + EventJournal
```

其中代码始终持久化在 Windows 的 `ai_generated_projects/<project_run_id>`，容器只提供隔离执行环境，并通过 `/workspace` 操作这份绑定挂载的工作树；删除容器不会删除代码，也没有额外 copy-back。平台以磁盘快照和 checkpoint 控制回滚与正式验收。

依赖侧只维护一份位于 E 盘 `.studio-cache/maven` 的受控共享 artifact store：DependencyCatalog 限制正式依赖与版本，平台 Resolver 是唯一写入者，Agent/验收容器默认断网并只读使用；允许的 cache miss 通过短时受控网络补入同一仓库，不允许为每个项目复制 `.m2`。依赖不可解析、网络不可用和缓存损坏分别归因，不能统一消耗模型修复预算。

Spring Boot 初始化侧不依赖运行时在线 Initializr。平台的 `ProjectScaffoldService` 相当于一个范围受控、可复现的离线脚手架生成器：复用现有 POM 模板能力并补齐入口、配置、目录与启动性测试，在零模型轮次内完成 Bootstrap；`ProjectProfileService.applySafeDefaults()` 从“模型生成后修补”改为“生成前编译 spec + 迁移期兜底”。这既降低基础编译失败，也把模型预算留给真正影响用户行为的业务实现。

质量控制也应保持最小化：第一版只有规划语义门、里程碑最小验证门和最终 required 行为验收三类硬门。VerificationBroker 的六类能力是按需选择的工具箱；Manifest、DiagnosticSet、日志、快照、沙箱和基准指标都不能被实现成新的质量关卡。增加检查数量本身不是目标，减少错误目标、缩短反馈距离和提高修复转化率才是保留这些机制的依据。

后续只有在上一阶段的目标指标发生预期变化时才继续：阶段二确实降低 turn-limit 后再引入 hardened Bash、结构化 patch 和 checkpoint；阶段三确实释放有效轮次后再建设完整诊断修复闭环；控制面稳定后才比较模型。某项改造若只增加代码量和门禁数量，却没有改善对应过程指标或独立行为通过率，应简化或撤回。

相比继续强化 prompt、增加 Agent 角色或把 24 轮提高到 48 轮，这条路线更可能带来真实效果，不是因为它更“严谨”，而是因为每一阶段都对应当前轨迹中的具体损耗，并且拥有可证伪的效果假设。

## 附录 A：关键源码索引

### SoftwareStudio-5

- `src/main/java/com/core/multiAgentSoftwareStudio/Service/Workflow/SoftwareStudioWorkflowService.java:82-144`：主工作流和切片不再作为执行边界。
- `src/main/java/com/core/multiAgentSoftwareStudio/Service/Generation/BatchGenerationService.java:27-55`：完整项目目标的一次性拼接。
- `src/main/java/com/core/multiAgentSoftwareStudio/Service/AgentRuntime/WorkspaceAgentRuntime.java:63-218`：工具循环、停止和未完成候选交接。
- `src/main/java/com/core/multiAgentSoftwareStudio/Config/WorkspaceAgentLimitsConfig.java:14-21,79-100`：轮次、工具、停滞与压缩配置。
- `src/main/java/com/core/multiAgentSoftwareStudio/Service/AgentRuntime/WorkspaceAgentContextCompactor.java:23-76`：固定轮次压缩策略。
- `src/main/java/com/core/multiAgentSoftwareStudio/Service/AgentRuntime/WorkspaceToolRegistry.java:80-340`：工具定义、读取、编辑和完成门。
- `src/main/java/com/core/multiAgentSoftwareStudio/Model/Generation/Contract/ApiEndpointContract.java:21-27`：危险的 GET `/` 默认值。
- `src/main/java/com/core/multiAgentSoftwareStudio/Model/Generation/Contract/FrontendCallContract.java:19-23`：前端调用的危险默认值。
- `src/main/java/com/core/multiAgentSoftwareStudio/Service/Repair/ToolDrivenRepairService.java:84-92`：当前在完整生产代码后才执行 Profile 默认值和持久化。
- `src/main/java/com/core/multiAgentSoftwareStudio/Service/Repair/ToolDrivenRepairService.java:94-223`：修复轮次与验证。
- `src/main/java/com/core/multiAgentSoftwareStudio/Service/Repair/RepairProgressEvaluator.java:14-31`：粗粒度进度判断。
- `src/main/java/com/core/multiAgentSoftwareStudio/Service/Workflow/FailureTriageService.java:21-41`：整段失败日志指纹。
- `src/main/java/com/core/multiAgentSoftwareStudio/Service/Benchmark/BenchmarkQualityEvaluator.java:27-107`：当前独立质量门。
- `src/main/java/com/core/multiAgentSoftwareStudio/Service/Contract/ProjectProfileService.java:56,119-160,175-215`：现有 POM 模板、后置默认值和 contextLoads 测试生成，是确定性脚手架可前移复用的基础。
- `src/main/resources/project-templates/java17-spring-boot/pom.xml`：现有 Java 17 Spring Boot 本地 POM 模板。
- `src/main/java/com/core/multiAgentSoftwareStudio/Tool/DockerSandboxService.java:257-340`：容器挂载、运行与清理。

### OpenCodeReview

- `internal/session/manifest.go`：sealed manifest、状态分区与终态计算。
- `internal/agent/identity.go`：输入身份与恢复边界。
- `internal/agent/agent.go`：预过滤、预算、并发、逐项完成与故障隔离。
- `internal/llmloop/loop.go`：显式停止、工具预算、no-call nudge 与 grace round。
- `internal/llmloop/compression.go`：token 阈值和完整轮次压缩。
- `internal/tool/file_read.go`、`internal/tool/code_search.go`：有界读取与成熟搜索。

### mini-SWE-agent

- `src/minisweagent/agents/default.py`：最小主循环和每步轨迹。
- `src/minisweagent/models/litellm_model.py`、`models/utils/retry.py`：模型适配与重试。
- `src/minisweagent/environments/local.py`、`docker.py`：环境抽象及其安全边界。
- `src/minisweagent/config/benchmarks/swebench.yaml`：检查—复现—修改—验证协议。
- `src/minisweagent/run/benchmarks/swebench.py`：外部基准与异常时产物保存。

### Codex CLI

- `codex-rs/core/src/tools/orchestrator.rs`、`sandboxing.rs`：策略、权限、沙箱和升级重试。
- `codex-rs/core/src/tools/parallel.rs`、`router.rs`：工具注册与读写并发。
- `codex-rs/apply-patch/src/lib.rs`、`parser.rs`：结构化补丁。
- `codex-rs/core/src/unified_exec/head_tail_buffer.rs`：有界长输出。
- `codex-rs/core/src/context_manager/history.rs`、`compact.rs`：成对历史与 token 压缩。
- `codex-rs/core/src/turn_diff_tracker.rs`：精确净 diff。
- `codex-rs/rollout/src/recorder.rs`：耐久追加式轨迹。

### Aider

- `aider/repomap.py`：tree-sitter 符号图、PageRank 和 token 预算 RepoMap。
- `aider/coders/architect_coder.py`：architect/editor 分离。
- `aider/coders/base_coder.py`：上下文、编辑、lint、test 和 reflection 主循环。
- `aider/coders/editblock_coder.py`：search/replace block、dry-run 和失败反馈。
- `aider/linter.py`：带代码上下文的 lint 结果。
