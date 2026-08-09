package com.core.multiAgentSoftwareStudio.Service.Contract;

import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ApiEndpointContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.FrontendCallContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractValidationServiceTest {

    private final ContractValidationService service = new ContractValidationService(new SourceCodePathService());

    /**
     * 投票案例暴露出的字段错位、未使用路径变量和静态首页冲突必须在 Docker 前形成确定性告警。
     */
    @Test
    void detectsFrontendPayloadAndControllerRouteDefects() {
        ProjectContract contract = new ProjectContract(
                List.of(new ApiEndpointContract(
                        "POST", "/api/polls/{id}/vote", "CastVoteRequest", "PollResponse",
                        "PollController", "vote")),
                List.of(),
                List.of(new FrontendCallContract(
                        "src/main/resources/static/app.js", "POST", "/api/polls/{id}/vote", "vote")),
                List.of());
        List<SourceCode> codes = List.of(
                new SourceCode("src/main/java/com/example/PollController.java", "java", """
                        package com.example;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        public class PollController {
                            @RequestMapping(value = "/", produces = "text/plain")
                            public String index() { return "api"; }
                            @PostMapping("/api/polls/{id}/vote")
                            public Object vote(@PathVariable Long id, @RequestBody CastVoteRequest request) {
                                return service.castVote(request);
                            }
                        }
                        """),
                new SourceCode("src/main/java/com/example/CastVoteRequest.java", "java", """
                        package com.example;
                        public class CastVoteRequest {
                            private String voterId;
                            private Long pollOptionId;
                        }
                        """),
                new SourceCode("src/main/resources/static/index.html", "html", "<main>poll</main>"),
                new SourceCode("src/main/resources/static/app.js", "javascript", """
                        fetch(`/api/polls/${pollId}/vote`, {
                            method: 'POST',
                            body: JSON.stringify({ voterId: 'u1', optionId: 3 })
                        });
                        """));

        List<String> warnings = service.validateProject(codes, contract);

        assertTrue(warnings.stream().anyMatch(value -> value.startsWith("Frontend request body field mismatch")));
        assertTrue(warnings.stream().anyMatch(value -> value.startsWith("Controller path variable `id`")));
        assertTrue(warnings.stream().anyMatch(value -> value.startsWith("Static index route conflict")));
    }

    /**
     * 契约声明前端必须调用 REST API 时，仅生成一个未使用的后端接口不能视为契约完成。
     */
    @Test
    void detectsDeclaredFrontendCallThatIsNotIssued() {
        ProjectContract contract = new ProjectContract(
                List.of(new ApiEndpointContract(
                        "GET", "/api/calculate", "", "Result", "CalculatorController", "calculate")),
                List.of(),
                List.of(new FrontendCallContract(
                        "src/main/resources/static/app.js", "GET", "/api/calculate", "calculate")),
                List.of());
        List<SourceCode> codes = List.of(
                new SourceCode("src/main/java/com/example/CalculatorController.java", "java", """
                        package com.example;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        public class CalculatorController {
                            @GetMapping("/api/calculate") public Object calculate() { return null; }
                        }
                        """),
                new SourceCode("src/main/resources/static/app.js", "javascript",
                        "document.querySelector('form').addEventListener('submit', validate);"));

        List<String> warnings = service.validateProject(codes, contract);

        assertTrue(warnings.stream().anyMatch(value -> value.contains("is declared but the source file does not issue")));
    }

    /**
     * 可确定识别的 Thymeleaf URL 嵌套表达式和编辑 textarea 丢值风险必须阻断后续成功判定。
     */
    @Test
    void detectsThymeleafEditFormRisks() {
        List<SourceCode> codes = List.of(new SourceCode(
                "src/main/resources/templates/article-form.html", "html", """
                        <form th:action="${article.id != null} ? @{/articles/${article.id}/edit} : @{/articles}">
                            <textarea name="content"></textarea>
                        </form>
                        """));

        List<String> warnings = service.validateProject(codes, null);

        assertTrue(warnings.stream().filter(value -> value.startsWith("Thymeleaf template risk")).count() >= 2);
    }

    /**
     * 标准 Thymeleaf 路径参数、MVC 表单和页面链接都应视为真实前端调用，不能形成契约误报。
     */
    @Test
    void acceptsStandardThymeleafMvcCalls() {
        ProjectContract contract = new ProjectContract(
                List.of(
                        new ApiEndpointContract("GET", "/articles/new", "", "View", "BlogController", "new"),
                        new ApiEndpointContract("GET", "/articles/{id}", "", "View", "BlogController", "detail"),
                        new ApiEndpointContract("POST", "/articles/{id}/delete", "", "View", "BlogController", "delete")),
                List.of(),
                List.of(
                        new FrontendCallContract("src/main/resources/templates/index.html", "GET",
                                "/articles/new", "new article"),
                        new FrontendCallContract("src/main/resources/templates/index.html", "GET",
                                "/articles/{id}", "article detail"),
                        new FrontendCallContract("src/main/resources/templates/index.html", "POST",
                                "/articles/{id}/delete", "delete article")),
                List.of());
        List<SourceCode> codes = List.of(
                new SourceCode("src/main/java/com/example/BlogController.java", "java", """
                        package com.example;
                        import org.springframework.stereotype.Controller;
                        import org.springframework.web.bind.annotation.*;
                        @Controller
                        public class BlogController {
                            @GetMapping("/articles/new") public String createForm() { return "index"; }
                            @GetMapping("/articles/{id}") public String detail(@PathVariable Long id) { return "index"; }
                            @PostMapping("/articles/{id}/delete") public String delete(@PathVariable Long id) { return "index"; }
                        }
                        """),
                new SourceCode("src/main/resources/templates/index.html", "html", """
                        <a href="/articles/new">New Article</a>
                        <a th:href="@{/articles/{id}(id=${article.id})}">Details</a>
                        <form th:action="@{/articles/{id}/delete(id=${article.id})}" method="post"></form>
                        """));

        List<String> warnings = service.validateProject(codes, contract);

        assertFalse(warnings.stream().anyMatch(value -> value.startsWith("Thymeleaf template risk")));
        assertFalse(warnings.stream().anyMatch(value -> value.contains("source file does not issue that request")));
    }
}
