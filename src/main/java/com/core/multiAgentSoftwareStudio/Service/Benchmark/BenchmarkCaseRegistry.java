package com.core.multiAgentSoftwareStudio.Service.Benchmark;

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
            BenchmarkGate.HAS_SPRING_CONTEXT_TEST);

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
                                请生成一个 Java 17、Spring Boot 的内存任务管理 REST API。
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
                        SPRING_BASELINE_GATES));
    }

    public List<BenchmarkCase> select(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return allCases();
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
