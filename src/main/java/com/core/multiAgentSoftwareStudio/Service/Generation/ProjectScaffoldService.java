package com.core.multiAgentSoftwareStudio.Service.Generation;

import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectProfile;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectScaffoldSpec;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Workflow.SandboxExecutionResult;
import com.core.multiAgentSoftwareStudio.Service.Contract.ProjectProfileService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 使用版本化本地模板在新工作区生成最小骨架，并在业务编辑前真实编译。
 */
@Service
public class ProjectScaffoldService {
    private static final String POM_TEMPLATE = "project-templates/java17-spring-boot/pom.xml";
    private final ProjectProfileService profileService;
    private final WorkspaceService workspaceService;
    private final DockerSandboxService sandbox;

    public ProjectScaffoldService(ProjectProfileService profileService, WorkspaceService workspaceService,
                                  DockerSandboxService sandbox) {
        this.profileService = profileService;
        this.workspaceService = workspaceService;
        this.sandbox = sandbox;
    }

    /**
     * 仅在初始工作区渲染模板；已有业务源码的目录不允许被骨架覆盖。
     */
    public SoftwareStudioWorkflowData bootstrap(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        ProjectProfile profile = profileService.resolve(data.structure);
        data.projectProfile = profile;
        if (!"SPRING_BOOT".equals(profile.projectType())) {
            logger.accept("   No stage-one scaffold for project type " + profile.projectType() + ".");
            return data;
        }
        String rootPackage = data.structure.rootPackage();
        String mainClass = data.structure.mainClassName();
        if (rootPackage == null || !rootPackage.matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*")) {
            throw new IllegalStateException("Scaffold needs a valid rootPackage: " + rootPackage);
        }
        if (mainClass == null || !mainClass.startsWith(rootPackage + ".")
                || !mainClass.substring(rootPackage.length() + 1).matches("[A-Za-z_$][\\w$]*")) {
            throw new IllegalStateException("Scaffold needs a mainClassName in rootPackage: " + mainClass);
        }
        Path root = Path.of(data.projectPath).toAbsolutePath().normalize();
        if (Files.exists(root.resolve("pom.xml"))) {
            // 重入工作流不能用模板覆盖已有项目，避免丢失人工或 Agent 修改。
            throw new IllegalStateException("Scaffold target already contains pom.xml: " + root);
        }
        String className = mainClass.substring(mainClass.lastIndexOf('.') + 1);
        String packagePath = rootPackage.replace('.', '/');
        String artifactId = artifactId(data.prd == null ? null : data.prd.projectName());
        ProjectScaffoldSpec spec = new ProjectScaffoldSpec(data.prd.projectName(), rootPackage, mainClass,
                profile, List.of("spring-boot-starter-web", "spring-boot-starter-validation",
                        "spring-boot-starter-test"));
        data.scaffoldSpec = spec;

        List<SourceCode> files = new ArrayList<>();
        String pom = loadPom().replace("<groupId>com.example</groupId>", "<groupId>" + rootPackage + "</groupId>")
                .replace("<artifactId>generated-application</artifactId>", "<artifactId>" + artifactId + "</artifactId>");
        files.add(new SourceCode("pom.xml", "xml", pom));
        files.add(new SourceCode("src/main/java/" + packagePath + "/" + className + ".java", "java",
                "package " + rootPackage + ";\n\n"
                        + "import org.springframework.boot.SpringApplication;\n"
                        + "import org.springframework.boot.autoconfigure.SpringBootApplication;\n\n"
                        + "@SpringBootApplication\npublic class " + className + " {\n"
                        + "    public static void main(String[] args) {\n"
                        + "        SpringApplication.run(" + className + ".class, args);\n"
                        + "    }\n}\n"));
        files.add(new SourceCode("src/main/resources/application.properties", "properties",
                "spring.application.name=" + artifactId + "\n"));
        files.add(new SourceCode("src/test/java/" + packagePath + "/" + className + "ContextTest.java", "java",
                "package " + rootPackage + ";\n\n"
                        + "import org.junit.jupiter.api.Test;\n"
                        + "import org.springframework.boot.test.context.SpringBootTest;\n\n"
                        + "@SpringBootTest(classes = " + className + ".class)\n"
                        + "class " + className + "ContextTest {\n"
                        + "    @Test void contextLoads() { }\n}\n"));
        files.add(new SourceCode(".gitignore", "text", "target/\n.idea/\n*.iml\n"));
        workspaceService.writeSourceFilesToDisk(root, files, logger);
        data.codes = workspaceService.loadProjectFromDisk(root, logger);

        // 初始编译只证明平台模板可构建，不把它计作业务功能完成。
        SandboxExecutionResult build = sandbox.runCompileInSandboxWithResult(root, profile.projectType());
        data.scaffoldBuild = build;
        logger.accept("   Scaffold compile: exit=" + build.exitCode() + ", duration="
                + build.durationMillis() + "ms, infrastructureError=" + build.infrastructureError());
        if (!build.completedSuccessfully()) {
            throw new IllegalStateException("Initial scaffold compile failed: "
                    + (build.infrastructureError() == null ? concise(build.output()) : build.infrastructureError()));
        }
        return data;
    }

    private String loadPom() {
        try {
            return new ClassPathResource(POM_TEMPLATE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load Spring Boot scaffold template", e);
        }
    }

    private String artifactId(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "generated-application" : normalized;
    }

    private String concise(String text) {
        if (text == null) return "";
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() > 300 ? compact.substring(0, 300) : compact;
    }
}
