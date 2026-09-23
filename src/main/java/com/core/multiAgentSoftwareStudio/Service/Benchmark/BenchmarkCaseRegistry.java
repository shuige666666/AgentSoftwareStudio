package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkCase;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkGate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 第一批稳定、小规模的基准任务。后续新增任务应保持需求文本和门禁不变，保证纵向可比。
 */
@Service
public class BenchmarkCaseRegistry {

    private static final Set<BenchmarkGate> SPRING_BASELINE_GATES = Set.of(
            BenchmarkGate.NO_PLACEHOLDER_SOURCE,
            BenchmarkGate.HAS_TEST_SOURCE,
            BenchmarkGate.TESTS_EXECUTED_AND_GREEN,
            BenchmarkGate.HAS_SPRING_CONTEXT_TEST,
            BenchmarkGate.CONTRACT_GATES_PASSED);

    public List<BenchmarkCase> allCases() {
        return List.of(
                new BenchmarkCase(
                        "calculator-web-basic",
                        "简单计算器 Web 应用",
                        """
                                请生成一个 Java 17、Spring Boot 的简单计算器 Web 应用。
                                页面支持两个数字的加减乘除；后端提供明确的 REST 接口；
                                必须提供 JUnit 5 测试和至少一个 Spring Boot 上下文启动测试。
                                页面调用的接口路径、参数名和错误处理必须与后端一致。
                                """,
                        SPRING_BASELINE_GATES),
                new BenchmarkCase(
                        "task-api-basic",
                        "任务管理 REST API",
                        """
                                请生成一个 Java 17、Spring Boot 的任务管理 REST API。
                                支持创建、查询列表、按 id 查询和完成任务；使用构造器注入；
                                必须提供 JUnit 5 测试和至少一个 Spring Boot 上下文启动测试。
                                所有接口路径、请求字段和响应字段需要保持一致。
                                """,
                        SPRING_BASELINE_GATES),
                new BenchmarkCase(
                        "snake-websocket-basic",
                        "贪吃蛇 WebSocket 最小闭环",
                        """
                                请生成一个 Java 17、Spring Boot 的网页贪吃蛇最小应用。
                                后端通过 WebSocket 推送游戏状态，前端可以发送方向和 RESTART 命令；
                                必须为核心规则、WebSocket 协议和 Spring Boot 上下文提供 JUnit 5 测试。
                                前端、后端和测试必须使用同一份消息契约。
                                """,
                        SPRING_BASELINE_GATES),
                new BenchmarkCase(
                        "url-shortener-basic",
                        "短链接服务",
                        """
                                请生成一个 Java 17、Spring Boot 的短链接服务。
                                支持生成短链接、访问短链接和查询链接信息。
                                需要处理无效链接，并提供必要的自动化测试。
                                """,
                        SPRING_BASELINE_GATES),
                new BenchmarkCase(
                        "url-shortener-behavior-v1",
                        "短链接服务行为基线 v1",
                        """
                                请生成 Java 17、Spring Boot 短链接服务，提供以下公开 HTTP 契约。
                                POST /api/links 接收 {"url":"https://example.org/docs"}，成功返回 201 和 JSON，
                                JSON 至少包含非空 code 及与请求相同的 originalUrl。
                                GET /api/links/{code} 返回 200 和相同的 code、originalUrl。
                                GET /{code} 返回 302，Location 为原始 URL。
                                不存在的 code 在查询和访问时均返回 404。
                                code 可安全放入 URL 路径；应用使用默认 HTTP 端口 8080。
                                可以使用内存存储；必须提供必要的自动化测试。
                                """,
                        SPRING_BASELINE_GATES),
                new BenchmarkCase(
                        "poll-system-basic",
                        "在线投票系统",
                        """
                                请生成一个 Java 17、Spring Boot 的在线投票系统。
                                支持创建投票、用户投票和查看投票结果。
                                需要防止用户重复投票，并提供必要的测试。
                                """,
                        SPRING_BASELINE_GATES),
                new BenchmarkCase(
                        "poll-system-behavior-v1",
                        "在线投票系统行为基线 v1",
                        """
                                请生成 Java 17、Spring Boot 在线投票系统，提供以下公开 HTTP 契约。
                                POST /api/polls 接收 {"question":"选项测试","options":["A","B"]}，
                                成功返回 201 和含非空 id 的 JSON。
                                POST /api/polls/{id}/votes 接收 {"voterId":"alice","option":"A"}，
                                成功返回 200 或 201；同一投票中相同 voterId 再次投票返回 409，且不能改变票数。
                                不同 voterId 可以分别投票。GET /api/polls/{id}/results 返回 200 和 JSON，
                                其中 counts 对象用选项文本作键，值为各选项票数；初始均为 0。
                                voterId 是本案例的身份依据，不要求登录或外部身份服务。
                                应用使用默认 HTTP 端口 8080。
                                可以使用内存存储；必须提供必要的自动化测试。
                                """,
                        SPRING_BASELINE_GATES),
                new BenchmarkCase(
                        "blog-system-basic",
                        "简单博客系统",
                        """
                                请生成一个 Java 17、Spring Boot 的简单博客系统。
                                支持文章的发布、修改、删除、查询和评论功能。
                                提供可使用的 Web 页面和必要的自动化测试。
                                """,
                        SPRING_BASELINE_GATES)
        );
    }

    public List<BenchmarkCase> select(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            // 新版行为案例显式选择，避免旧的默认六案例请求意外增加远程模型费用。
            return allCases().stream()
                    .filter(benchmarkCase -> !BenchmarkBehaviorEvaluator.isBehaviorCase(benchmarkCase.id()))
                    .toList();
        }
        List<BenchmarkCase> selected = allCases().stream()
                .filter(benchmarkCase -> ids.contains(benchmarkCase.id()))
                .toList();
        if (selected.size() != ids.size()) {
            throw new IllegalArgumentException("Unknown benchmark case id. Available: "
                    + allCases().stream().map(BenchmarkCase::id).toList());
        }
        return selected;
    }
}
