package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.core.multiAgentSoftwareStudio.SoftwareStudioApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.*;
import java.nio.file.*;

/** 用修正后的外部判卷复查保存产物，保持候选源码和原实验报告不变。 */
public final class SavedArtifactBehaviorCheck {
    /** 手工入口只运行本机 Docker/HTTP 判卷；没有开发或规划模型调用。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !BenchmarkBehaviorEvaluator.isBehaviorCase(args[0]))
            throw new IllegalArgumentException("Usage: SavedArtifactBehaviorCheck <case-id> <saved-project> <new-report.json>");
        Path output = Path.of(args[2]).toAbsolutePath();
        if (Files.exists(output)) throw new IllegalStateException("Preserve existing evidence: " + output);
        var app = new SpringApplication(SoftwareStudioApplication.class); app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run("--spring.main.banner-mode=off")) {
            var result = context.getBean(BenchmarkBehaviorEvaluator.class).evaluate(args[0], args[1]);
            Files.createDirectories(output.getParent());
            context.getBean(ObjectMapper.class).writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
            System.out.println("SAVED_BEHAVIOR_RESULT=" + result.status());
        }
    }
}
