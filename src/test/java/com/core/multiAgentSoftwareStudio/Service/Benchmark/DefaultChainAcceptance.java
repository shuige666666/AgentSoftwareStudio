package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentLimitsConfig;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkRunRequest;
import com.core.multiAgentSoftwareStudio.SoftwareStudioApplication;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 第四步的有限端到端验收入口，调用生产主链，不被 Maven 单元测试自动执行。 */
public final class DefaultChainAcceptance {
    private static final Map<String, String> SLOTS = Map.of(
            "short-1", "url-shortener-behavior-v1",
            "short-2", "url-shortener-behavior-v1",
            "blog-1", "blog-form-behavior-v1");

    /** 每个槽位从需求重新规划、搭建脚手架并开发，保留独立报告，不能覆盖已有证据。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !SLOTS.containsKey(args[1])) {
            throw new IllegalArgumentException("Usage: DefaultChainAcceptance <log-root> <short-1|short-2|blog-1>");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path slot = Files.createDirectory(root.resolve(args[1]));
        var app = new SpringApplication(SoftwareStudioApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run("--spring.main.banner-mode=off",
                "--studio.agent-runtime.audit.root-directory=" + slot.resolve("audit"))) {
            if (!context.getBean(DockerSandboxService.class).isDockerAvailable()) {
                throw new IllegalStateException("Docker must be available before starting remote generation");
            }
            var mapper = context.getBean(ObjectMapper.class);
            var limits = context.getBean(WorkspaceAgentLimitsConfig.class).getDevelopment();
            mapper.writerWithDefaultPrettyPrinter().writeValue(slot.resolve("conditions.json").toFile(), Map.of(
                    "caseId", SLOTS.get(args[1]), "strategy", "production-default",
                    "planning", "fresh", "scaffold", "fresh", "cost", "UNKNOWN",
                    "maxModelTurns", limits.getMaxModelTurns(), "maxToolCalls", limits.getMaxToolCalls()));

            // 使用现有基准入口，保留平台验收、独立判卷、模型分配和实际用量。
            var report = context.getBean(BenchmarkRunnerService.class).run(new BenchmarkRunRequest(
                    List.of(SLOTS.get(args[1])), 1, Map.of()));
            mapper.writerWithDefaultPrettyPrinter().writeValue(slot.resolve("report.json").toFile(), report);
            System.out.println("DEFAULT_CHAIN_REPORT=" + report.reportPath());
            System.out.println("DEFAULT_CHAIN_COMPLETE=" + report.fullWorkflowSuccessRate());
        }
    }
}
