# SoftwareStudio 生成上下文

该上下文定义 SoftwareStudio 对生成项目的正式能力边界，避免“可以尝试生成”与“平台提供质量保证”被混为一谈。

## Language

**正式支持项目（Supported Project）**：
符合平台固定 Java、Spring Boot 与 Maven Profile，并拥有完整规划、生成、独立验收和预览能力的 Web 或 REST 项目。
_Avoid_: 所有 Java 项目、任意生成项目

**实验项目（Experimental Project）**：
平台可以尝试生成和保留产物，但尚无类型专属验收体系、不得使用正式成功声明的纯 Java Maven 或原生 Java 项目。
_Avoid_: 正式支持项目、已验收项目

**平台 Profile（Platform Profile）**：
正式支持项目必须遵守的固定运行时、框架、构建工具和验证能力集合。
_Avoid_: 模型建议技术栈、任意依赖组合

**封闭需求输入（Closed Requirement Input）**：
一次生成任务开始时冻结且在本轮运行中不再向用户追问的需求文本或基准案例。
_Avoid_: 可交互需求会话、等待用户确认

**显式生成假设（Explicit Generation Assumption）**：
对封闭需求未指定的非关键细节所采用、并记录在生成结果中的平台约定。
_Avoid_: 静默伪造必填契约、用户确认结果

## Relationships

- 一个生成任务只对应一个 **平台 Profile**
- 只有 **正式支持项目** 才能进入正式质量验收并产生正式成功状态
- **实验项目** 可以保留候选代码和诊断结果，但不能冒充 **正式支持项目**
- 第一版 **平台 Profile** 聚焦 Java Spring Boot Maven Web/REST 项目
- 第一版所有交互生成和批量基准都使用 **封闭需求输入**，运行中不会等待用户回答
- **封闭需求输入**中的非关键缺省可以形成 **显式生成假设**；规划对象自身的必填字段缺失仍必须重规划或失败

## Example dialogue

> **Dev:** “架构 Agent 返回了 `PURE_JAVA_MAVEN`，代码也成功编译，可以标记生成成功吗？”
> **Domain expert:** “不能标记为正式成功；第一版只有匹配 Spring Boot Maven Profile 且通过独立验收的项目属于正式支持项目，这个产物应标记为实验项目。”

> **Dev:** “需求没有说明数据库类型，批量任务需要暂停等待用户选择吗？”
> **Domain expert:** “第一版不暂停；把固定 Profile 的数据库选择记录为显式生成假设。若规划 Agent 连 HTTP method 这类必填契约都没有给出，则重规划或失败，不能把它伪装成用户选择。”

## Flagged ambiguities

- 现有 Architect 声明支持 `SPRING_BOOT`、`PURE_JAVA_MAVEN` 和 `PURE_JAVA_NATIVE`，但正式基准、确定性 Profile、契约检查和预览主要覆盖 Spring Boot；已解决：重构第一版只对 Java Spring Boot Maven Web/REST 项目提供正式质量保证，其他两类暂为实验能力。
- 需求歧义原计划可进入用户追问，但这会阻塞无人值守批量基准；已解决：第一版采用封闭需求输入，不实现中途追问，需求澄清与可恢复问答推迟到生成质量稳定之后。
