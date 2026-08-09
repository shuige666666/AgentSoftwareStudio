package com.core.multiAgentSoftwareStudio.Service.Contract;

import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectProfile;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectQualityPolicyResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Generation.QualityGateSeverity;
import com.core.multiAgentSoftwareStudio.Model.Generation.QualityPolicyFinding;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/**
 * 为生成项目选择固定 Profile、应用安全的模板归一化，并执行确定性前置门禁。
 */
@Service
public class ProjectProfileService {

    private static final Pattern SPRING_BOOT_PARENT_VERSION = Pattern.compile(
            "(?s)(<parent>.*?<artifactId>\\s*spring-boot-starter-parent\\s*</artifactId>.*?<version>\\s*)[^<]+(\\s*</version>.*?</parent>)");
    private static final Pattern JAVA_VERSION = Pattern.compile(
            "(?s)(<java.version>\\s*)[^<]+(\\s*</java.version>)");
    private static final Pattern INVALID_WEBSOCKET_TEST_DEPENDENCY = Pattern.compile(
            "(?s)\\s*<dependency>\\s*<groupId>org\\.springframework</groupId>\\s*"
                    + "<artifactId>spring-websocket-test</artifactId>.*?</dependency>");
    private static final String STARTER_TEST_ARTIFACT = "<artifactId>spring-boot-starter-test</artifactId>";
    private static final String STARTER_TEST_DEPENDENCY = """
            
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-test</artifactId>
                        <scope>test</scope>
                    </dependency>
            """;
    private static final String TEMPLATE_PATH = "project-templates/java17-spring-boot/pom.xml";

    private final ContractValidationService contractValidationService;
    private final SourceCodePathService sourceCodePathService;

    public ProjectProfileService(
            ContractValidationService contractValidationService,
            SourceCodePathService sourceCodePathService) {
        this.contractValidationService = contractValidationService;
        this.sourceCodePathService = sourceCodePathService;
    }

    /**
     * 根据架构结果选择固定工程 Profile；当前 Spring Boot 基准统一使用 Java 17 模板。
     */
    public ProjectProfile resolve(ProjectStructure structure) {
        String projectType = structure == null ? null : structure.projectType();
        return "SPRING_BOOT".equals(projectType)
                ? ProjectProfile.java17SpringBoot()
                : ProjectProfile.generic(projectType);
    }

    /**
     * 应用不会改变业务语义的兼容性修复，消除已知版本组合和测试注解错误。
     */
    public List<String> applySafeDefaults(ProjectProfile profile, List<SourceCode> codes) {
        if (profile == null || codes == null || !"SPRING_BOOT".equals(profile.projectType())) {
            return List.of();
        }

        List<String> changedFiles = new ArrayList<>();
        SourceCode pom = findByPath(codes, "pom.xml");
        if (pom == null || pom.code() == null || pom.code().isBlank()) {
            sourceCodePathService.upsertSourceCode(codes, "pom.xml", loadTemplate());
            changedFiles.add("pom.xml");
        } else {
            String normalizedPom = normalizePom(pom.code(), profile);
            if (!normalizedPom.equals(pom.code())) {
                sourceCodePathService.upsertSourceCode(codes, "pom.xml", normalizedPom);
                changedFiles.add("pom.xml");
            }
        }

        for (int index = 0; index < codes.size(); index++) {
            SourceCode source = codes.get(index);
            if (source == null || source.code() == null || !isTestJava(source.filename())) {
                continue;
            }
            String normalized = source.code()
                    .replace("org.springframework.boot.test.mock.bean.MockBean",
                            "org.springframework.boot.test.mock.mockito.MockBean")
                    .replace("org.springframework.test.context.bean.override.mockito.MockitoBean",
                            "org.springframework.boot.test.mock.mockito.MockBean")
                    .replace("@MockitoBean", "@MockBean");
            if (!normalized.equals(source.code())) {
                codes.set(index, new SourceCode(source.filename(), source.language(), normalized));
                changedFiles.add(sourceCodePathService.normalizePath(source.filename()));
            }
        }
        return List.copyOf(changedFiles);
    }

    /**
     * 执行 Profile、POM、测试骨架和现有契约检查；BLOCK 项会阻止进入 Docker。
     */
    public ProjectQualityPolicyResult evaluate(
            ProjectProfile profile,
            List<SourceCode> codes,
            ProjectContract contract,
            List<String> normalizedFiles) {
        List<QualityPolicyFinding> findings = new ArrayList<>();
        List<SourceCode> safeCodes = codes == null ? List.of() : codes;

        if (profile != null && "SPRING_BOOT".equals(profile.projectType())) {
            evaluateSpringProfile(profile, safeCodes, findings);
        }

        for (String warning : contractValidationService.validateProject(safeCodes, contract)) {
            QualityGateSeverity severity = isBlockingContractWarning(warning)
                    ? QualityGateSeverity.BLOCK
                    : QualityGateSeverity.NEEDS_REVIEW;
            findings.add(new QualityPolicyFinding(
                    contractGateId(warning),
                    severity,
                    FailureKind.CONTRACT,
                    warning));
        }

        boolean passed = findings.stream().noneMatch(finding -> finding.severity() == QualityGateSeverity.BLOCK);
        return new ProjectQualityPolicyResult(passed, findings, normalizedFiles);
    }

    private void evaluateSpringProfile(
            ProjectProfile profile,
            List<SourceCode> codes,
            List<QualityPolicyFinding> findings) {
        SourceCode pom = findByPath(codes, "pom.xml");
        if (pom == null || pom.code() == null || pom.code().isBlank()) {
            block(findings, "PROFILE_POM", FailureKind.BUILD_PROFILE, "Spring Boot 项目缺少 pom.xml。 ");
            return;
        }

        String pomCode = pom.code();
        if (!isWellFormedXml(pomCode)) {
            block(findings, "PROFILE_POM_XML", FailureKind.BUILD_PROFILE, "pom.xml 不是合法 XML。");
        }
        if (!Pattern.compile("<java.version>\\s*" + profile.javaVersion() + "\\s*</java.version>")
                .matcher(pomCode).find()) {
            block(findings, "PROFILE_JAVA_VERSION", FailureKind.BUILD_PROFILE,
                    "pom.xml 必须使用 Java " + profile.javaVersion() + "。");
        }
        if (profile.springBootVersion() != null
                && !containsSpringBootParentVersion(pomCode, profile.springBootVersion())) {
            block(findings, "PROFILE_SPRING_BOOT_VERSION", FailureKind.BUILD_PROFILE,
                    "Spring Boot 父版本必须固定为 " + profile.springBootVersion() + "。");
        }
        if (pomCode.contains("<artifactId>spring-websocket-test</artifactId>")) {
            block(findings, "PROFILE_FORBIDDEN_DEPENDENCY", FailureKind.BUILD_PROFILE,
                    "spring-websocket-test 不是当前 Profile 允许的依赖。");
        }
        if (!pomCode.contains(STARTER_TEST_ARTIFACT)) {
            block(findings, "PROFILE_TEST_DEPENDENCY", FailureKind.BUILD_PROFILE,
                    "Spring Boot 项目缺少 spring-boot-starter-test。");
        }

        boolean hasTests = codes.stream().anyMatch(source -> source != null && isTestJava(source.filename()));
        if (!hasTests) {
            block(findings, "PROFILE_TEST_SOURCE", FailureKind.TEST_DISCOVERY,
                    "Spring Boot 项目没有 src/test/java 下的测试源码。");
        }
        boolean hasContextTest = codes.stream()
                .filter(source -> source != null && isTestJava(source.filename()))
                .map(source -> source.code() == null ? "" : source.code())
                .anyMatch(code -> code.contains("@SpringBootTest"));
        if (profile.requireSpringContextTest() && !hasContextTest) {
            block(findings, "PROFILE_SPRING_CONTEXT_TEST", FailureKind.TEST_DISCOVERY,
                    "Spring Boot Profile 要求至少一个 @SpringBootTest 上下文启动测试。");
        }

        for (SourceCode source : codes) {
            String code = source == null || source.code() == null ? "" : source.code();
            if (code.contains("org.springframework.test.context.bean.override.mockito.MockitoBean")
                    || code.contains("org.springframework.boot.test.mock.bean.MockBean")) {
                block(findings, "PROFILE_TEST_ANNOTATION", FailureKind.TEST_COMPILE,
                        "测试文件使用了与 Spring Boot 3.2 不兼容的 Mock Bean 注解："
                                + (source == null ? "unknown" : source.filename()));
            }
        }
    }

    private String normalizePom(String pom, ProjectProfile profile) {
        String normalized = replaceGroup(SPRING_BOOT_PARENT_VERSION, pom, profile.springBootVersion());
        normalized = replaceGroup(JAVA_VERSION, normalized, Integer.toString(profile.javaVersion()));
        normalized = INVALID_WEBSOCKET_TEST_DEPENDENCY.matcher(normalized).replaceAll("");
        if (!normalized.contains(STARTER_TEST_ARTIFACT) && normalized.contains("</dependencies>")) {
            normalized = normalized.replace("</dependencies>", STARTER_TEST_DEPENDENCY + "    </dependencies>");
        }
        return normalized;
    }

    private String replaceGroup(Pattern pattern, String value, String replacementValue) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find() || replacementValue == null) {
            return value;
        }
        return matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + replacementValue + matcher.group(2)));
    }

    private boolean containsSpringBootParentVersion(String pom, String expectedVersion) {
        Pattern expectedParent = Pattern.compile(
                "(?s)<parent>.*?<artifactId>\\s*spring-boot-starter-parent\\s*</artifactId>.*?"
                        + "<version>\\s*" + Pattern.quote(expectedVersion) + "\\s*</version>.*?</parent>");
        return expectedParent.matcher(pom).find();
    }

    private boolean isWellFormedXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            builder.parse(new InputSource(new StringReader(xml)));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isBlockingContractWarning(String warning) {
        String lower = warning == null ? "" : warning.toLowerCase(Locale.ROOT);
        return lower.startsWith("missing generated file")
                || lower.startsWith("placeholder implementation")
                || lower.startsWith("missing package declaration")
                || lower.startsWith("package/path mismatch")
                || lower.startsWith("public type name mismatch")
                || lower.startsWith("main source file")
                || lower.startsWith("contract endpoint")
                || lower.startsWith("contract view")
                || lower.startsWith("contract frontend call")
                || lower.startsWith("frontend file")
                || lower.startsWith("frontend request body field mismatch")
                || lower.startsWith("controller path variable")
                || lower.startsWith("static index route conflict")
                || lower.startsWith("thymeleaf template risk");
    }

    /**
     * 将可公开聚合的契约问题映射为稳定门禁 ID，报告无需保存具体错误文本。
     */
    private String contractGateId(String warning) {
        String lower = warning == null ? "" : warning.toLowerCase(Locale.ROOT);
        if (lower.startsWith("frontend request body field mismatch")) {
            return "FRONTEND_PAYLOAD_FIELDS";
        }
        if (lower.startsWith("controller path variable")) {
            return "CONTROLLER_PATH_VARIABLE_USAGE";
        }
        if (lower.startsWith("static index route conflict")) {
            return "STATIC_INDEX_ROUTE";
        }
        if (lower.startsWith("thymeleaf template risk")) {
            return "THYMELEAF_TEMPLATE";
        }
        if (lower.startsWith("contract frontend call")) {
            return "CONTRACT_FRONTEND_CALL";
        }
        if (lower.startsWith("frontend file")) {
            return "FRONTEND_ENDPOINT_MAPPING";
        }
        return "CONTRACT_VALIDATION";
    }

    private SourceCode findByPath(List<SourceCode> codes, String path) {
        return codes.stream()
                .filter(source -> source != null)
                .filter(source -> sourceCodePathService.normalizePath(source.filename()).equals(path))
                .findFirst()
                .orElse(null);
    }

    private boolean isTestJava(String filename) {
        return sourceCodePathService.normalizePath(filename).startsWith("src/test/java/")
                && sourceCodePathService.normalizePath(filename).endsWith(".java");
    }

    private String loadTemplate() {
        try {
            return new ClassPathResource(TEMPLATE_PATH).getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("无法读取 Java 17 Spring Boot 项目模板", e);
        }
    }

    private void block(
            List<QualityPolicyFinding> findings,
            String gate,
            FailureKind failureKind,
            String evidence) {
        findings.add(new QualityPolicyFinding(gate, QualityGateSeverity.BLOCK, failureKind, evidence));
    }
}
