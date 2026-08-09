package com.core.multiAgentSoftwareStudio.Service.Contract;

import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectProfile;
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
}
