package com.core.multiAgentSoftwareStudio.Service.Contract;

import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectProfile;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Generation.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectProfileServiceTest {

    private final SourceCodePathService pathService = new SourceCodePathService();
    private final ProjectProfileService service = new ProjectProfileService(
            new ContractValidationService(pathService), pathService);

    /**
     * Profile 应归一化 Spring Boot/Java 版本、移除虚构依赖并修正测试注解。
     */
    @Test
    void normalizesKnownSpringCompatibilityProblems() {
        List<SourceCode> codes = new ArrayList<>(List.of(
                new SourceCode("pom.xml", "xml", """
                        <project><parent><groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId><version>3.2.0</version></parent>
                        <properties><java.version>21</java.version></properties><dependencies>
                        <dependency><groupId>org.springframework</groupId><artifactId>spring-websocket-test</artifactId></dependency>
                        </dependencies></project>
                        """),
                new SourceCode("src/test/java/com/example/AppTest.java", "java", """
                        import org.springframework.test.context.bean.override.mockito.MockitoBean;
                        class AppTest { @MockitoBean Object service; }
                        """)));

        List<String> changed = service.applySafeDefaults(ProjectProfile.java17SpringBoot(), codes);
        String pom = codes.stream().filter(code -> code.filename().equals("pom.xml")).findFirst().orElseThrow().code();
        String test = codes.stream().filter(code -> code.filename().endsWith("AppTest.java")).findFirst().orElseThrow().code();

        assertTrue(changed.contains("pom.xml"));
        assertTrue(pom.contains("<version>3.2.4</version>"));
        assertTrue(pom.contains("<java.version>17</java.version>"));
        assertFalse(pom.contains("spring-websocket-test"));
        assertTrue(pom.contains("spring-boot-starter-test"));
        assertTrue(test.contains("org.springframework.boot.test.mock.mockito.MockBean"));
        assertTrue(test.contains("@MockBean"));
    }

    /**
     * 已知无效的重定向 builder 应确定性改写，避免进入 Debugger 后因无变化提前停止。
     */
    @Test
    void normalizesInvalidTemporaryRedirectBuilderWithoutLlm() {
        List<SourceCode> codes = new ArrayList<>(List.of(new SourceCode(
                "src/main/java/com/example/UrlController.java", "java", """
                        package com.example;
                        import org.springframework.http.ResponseEntity;
                        class UrlController {
                            Object redirect(java.net.URI uri) {
                                return ResponseEntity.temporaryRedirect(uri).build();
                            }
                        }
                        """)));

        List<String> changed = service.applySafeDefaults(ProjectProfile.java17SpringBoot(), codes);

        assertTrue(changed.contains("src/main/java/com/example/UrlController.java"));
        assertTrue(codes.getFirst().code().contains(
                "ResponseEntity.status(org.springframework.http.HttpStatus.TEMPORARY_REDIRECT).location(uri).build()"));
    }

    /**
     * 缺少 POM 时使用固定模板，避免让模型继续猜测完整工程骨架。
     */
    @Test
    void createsPomFromTemplateWhenMissing() {
        List<SourceCode> codes = new ArrayList<>(List.of(
                new SourceCode("src/test/java/com/example/AppTest.java", "java", "class AppTest {}")));

        service.applySafeDefaults(ProjectProfile.java17SpringBoot(), codes);

        assertTrue(codes.stream().anyMatch(code -> code.filename().equals("pom.xml")
                && code.code().contains("spring-boot-starter-parent")
                && code.code().contains("<java.version>17</java.version>")));
    }

    /**
     * Spring Boot 入口存在但缺少上下文测试时应确定性增量补建，且不改写已有业务测试。
     */
    @Test
    void addsDeterministicSpringContextTestWithoutLlm() {
        String existingTest = "package com.example; class PollServiceTest { void createsPoll() {} }";
        List<SourceCode> codes = new ArrayList<>(List.of(
                new SourceCode("pom.xml", "xml", """
                        <project><parent><groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId><version>3.2.4</version></parent>
                        <properties><java.version>17</java.version></properties><dependencies>
                        <dependency><artifactId>spring-boot-starter-test</artifactId></dependency>
                        </dependencies></project>
                        """),
                new SourceCode("src/main/java/com/example/VotingApplication.java", "java", """
                        package com.example;
                        import org.springframework.boot.autoconfigure.SpringBootApplication;
                        @SpringBootApplication
                        public class VotingApplication { }
                        """),
                new SourceCode("src/test/java/com/example/PollServiceTest.java", "java", existingTest)));

        List<String> changed = service.applySafeDefaults(ProjectProfile.java17SpringBoot(), codes);

        String contextPath = "src/test/java/com/example/VotingApplicationContextTest.java";
        assertTrue(changed.contains(contextPath));
        assertTrue(codes.stream().anyMatch(code -> contextPath.equals(code.filename())
                && code.code().contains("@SpringBootTest(classes = VotingApplication.class)")));
        assertTrue(codes.stream().anyMatch(code -> "src/test/java/com/example/PollServiceTest.java".equals(code.filename())
                && existingTest.equals(code.code())));
    }

    /**
     * 已生成源码明确使用持久化、校验和模板时，Profile 应确定性补齐所需 Starter。
     */
    @Test
    void addsDependenciesRequiredByGeneratedSources() {
        List<SourceCode> codes = new ArrayList<>(List.of(
                new SourceCode("pom.xml", "xml", """
                        <project><parent><groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId><version>3.2.4</version></parent>
                        <properties><java.version>17</java.version></properties><dependencies>
                        <dependency><artifactId>spring-boot-starter-web</artifactId></dependency>
                        <dependency><artifactId>spring-boot-starter-test</artifactId></dependency>
                        </dependencies></project>
                        """),
                new SourceCode("src/main/java/com/example/Article.java", "java",
                        "import jakarta.persistence.Entity; @Entity class Article {}"),
                new SourceCode("src/main/java/com/example/Controller.java", "java",
                        "import jakarta.validation.Valid; class Controller {}"),
                new SourceCode("src/main/resources/templates/article.html", "html", "<html></html>")));

        service.applySafeDefaults(ProjectProfile.java17SpringBoot(), codes);
        String pom = codes.getFirst().code();

        assertTrue(pom.contains("spring-boot-starter-data-jpa"));
        assertTrue(pom.contains("<artifactId>h2</artifactId>"));
        assertTrue(pom.contains("spring-boot-starter-validation"));
        assertTrue(pom.contains("spring-boot-starter-thymeleaf"));
    }

    /**
     * 无法被安全归一化的 POM 或缺失测试源码必须作为 BLOCK 门禁返回。
     */
    @Test
    void blocksMalformedPomAndMissingTests() {
        List<SourceCode> codes = List.of(new SourceCode("pom.xml", "xml", "<project>"));

        var result = service.evaluate(ProjectProfile.java17SpringBoot(), codes, null, List.of());

        assertFalse(result.passed());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.gate().equals("PROFILE_POM_XML")));
        assertTrue(result.findings().stream().anyMatch(finding -> finding.gate().equals("PROFILE_TEST_SOURCE")));
    }

    /**
     * 普通单元测试不能替代 Spring Boot 上下文启动门禁。
     */
    @Test
    void blocksSpringProjectWithoutContextTest() {
        List<SourceCode> codes = List.of(
                new SourceCode("pom.xml", "xml", """
                        <project><parent><groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId><version>3.2.4</version></parent>
                        <properties><java.version>17</java.version></properties><dependencies>
                        <dependency><groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-test</artifactId></dependency>
                        </dependencies></project>
                        """),
                new SourceCode("src/test/java/com/example/AppTest.java", "java", "class AppTest { @Test void unit() {} }"));

        var result = service.evaluate(ProjectProfile.java17SpringBoot(), codes, null, List.of());

        assertFalse(result.passed());
        assertTrue(result.findings().stream()
                .anyMatch(finding -> finding.gate().equals("PROFILE_SPRING_CONTEXT_TEST")));
    }

    /**
     * 前端字段错位应使用稳定门禁 ID 聚合，报告无需保存具体字段和值。
     */
    @Test
    void mapsFrontendPayloadWarningToStableGateId() {
        ContractValidationService validationService = mock(ContractValidationService.class);
        when(validationService.validateProject(any(), any())).thenReturn(List.of(
                "Frontend request body field mismatch for `POST /vote`: unknown=[optionId], missing=[pollOptionId]."));
        ProjectProfileService profileService = new ProjectProfileService(validationService, pathService);

        var result = profileService.evaluate(
                ProjectProfile.generic("OTHER"),
                List.of(new SourceCode("src/main/resources/static/app.js", "javascript", "fetch('/vote')")),
                null, List.of());

        assertFalse(result.passed());
        assertTrue(result.findings().stream()
                .anyMatch(finding -> finding.gate().equals("FRONTEND_PAYLOAD_FIELDS")));
    }

    /**
     * 缺失 Thymeleaf 表单模型必须成为独立阻断门禁，便于报告聚合和定向修复。
     */
    @Test
    void mapsMissingThymeleafModelAttributeToStableGateId() {
        ContractValidationService validationService = mock(ContractValidationService.class);
        when(validationService.validateProject(any(), any())).thenReturn(List.of(
                "Thymeleaf model attribute `createUrlRequest` used by templates/index.html is not provided."));
        ProjectProfileService profileService = new ProjectProfileService(validationService, pathService);

        var result = profileService.evaluate(ProjectProfile.generic("OTHER"), List.of(), null, List.of());

        assertFalse(result.passed());
        assertTrue(result.findings().stream()
                .anyMatch(finding -> finding.gate().equals("THYMELEAF_MODEL_ATTRIBUTE")));
    }

    /**
     * 重复路由和未注册 Bean 必须使用独立稳定门禁，避免都退化成通用契约错误。
     */
    @Test
    void mapsRuntimeStartupRisksToStableGateIds() {
        ContractValidationService validationService = mock(ContractValidationService.class);
        when(validationService.validateProject(any(), any())).thenReturn(List.of(
                "Duplicate controller mapping `GET /{}` is declared twice.",
                "Spring bean dependency `SnakeGame` injected into `SnakeHandler` is not registered."));
        ProjectProfileService profileService = new ProjectProfileService(validationService, pathService);

        var result = profileService.evaluate(ProjectProfile.generic("OTHER"), List.of(), null, List.of());

        assertFalse(result.passed());
        assertTrue(result.findings().stream()
                .anyMatch(finding -> finding.gate().equals("DUPLICATE_CONTROLLER_MAPPING")));
        assertTrue(result.findings().stream()
                .anyMatch(finding -> finding.gate().equals("SPRING_BEAN_DEPENDENCY")));
    }

    /**
     * Spring API、重定向语义和 404 映射风险应各自聚合，便于失败路由定位到确定性契约问题。
     */
    @Test
    void mapsHttpRuntimeRisksToStableGateIds() {
        ContractValidationService validationService = mock(ContractValidationService.class);
        when(validationService.validateProject(any(), any())).thenReturn(List.of(
                "Invalid Spring API usage in UrlController.java: invalid builder.",
                "Redirect endpoint `GET /{}` in UrlController.java returns void.",
                "Not-found exception mapping missing for `UrlNotFoundException`."));
        ProjectProfileService profileService = new ProjectProfileService(validationService, pathService);

        var result = profileService.evaluate(ProjectProfile.generic("OTHER"), List.of(), null, List.of());

        assertFalse(result.passed());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.gate().equals("SPRING_API_USAGE")));
        assertTrue(result.findings().stream().anyMatch(finding -> finding.gate().equals("REDIRECT_RESPONSE")));
        assertTrue(result.findings().stream()
                .anyMatch(finding -> finding.gate().equals("NOT_FOUND_EXCEPTION_MAPPING")));
    }

    /**
     * Spring Boot 扩展写法应归一化；声明异常时可由 Application 与配置文件的组合证据恢复。
     */
    @Test
    void normalizesSpringProjectTypeAliasesAndBlueprintEvidence() {
        var alias = new ProjectStructure("com.example", "spring boot mvc", "com.example.App", List.of());
        var inferred = new ProjectStructure("com.example", "WEB_APPLICATION", "com.example.BlogApplication", List.of(
                new FileBlueprint("BlogApplication.java", "src/main/java/com/example/BlogApplication.java",
                        "base", "base", "app", List.of(), List.of()),
                new FileBlueprint("application.properties", "src/main/resources/application.properties",
                        "config", "config", "config", List.of(), List.of())));

        assertTrue(service.resolve(alias).projectType().equals("SPRING_BOOT"));
        assertTrue(service.resolve(inferred).projectType().equals("SPRING_BOOT"));
    }

    /**
     * 无法识别的项目类型必须在 Docker 前阻断，不能静默进入原生 javac 分支。
     */
    @Test
    void blocksUnsupportedProjectType() {
        var result = service.evaluate(ProjectProfile.generic("WEB_APPLICATION"), List.of(), null, List.of());

        assertFalse(result.passed());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.gate().equals("PROFILE_PROJECT_TYPE")));
    }
}
