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

    /**
     * 创建和编辑共用表单时，条件表达式必须包含两个完整的 Thymeleaf URL，确保两条契约调用都可验证。
     */
    @Test
    void acceptsConditionalThymeleafFormActions() {
        ProjectContract contract = new ProjectContract(
                List.of(
                        new ApiEndpointContract("POST", "/articles", "ArticleForm", "View",
                                "ArticleController", "create"),
                        new ApiEndpointContract("POST", "/articles/{id}/edit", "ArticleForm", "View",
                                "ArticleController", "update")),
                List.of(),
                List.of(
                        new FrontendCallContract("src/main/resources/templates/article-form.html", "POST",
                                "/articles", "create article"),
                        new FrontendCallContract("src/main/resources/templates/article-form.html", "POST",
                                "/articles/{id}/edit", "update article")),
                List.of());
        List<SourceCode> codes = List.of(
                new SourceCode("src/main/java/com/example/ArticleController.java", "java", """
                        package com.example;
                        import org.springframework.stereotype.Controller;
                        import org.springframework.web.bind.annotation.*;
                        @Controller
                        public class ArticleController {
                            @PostMapping("/articles") public String create() { return "index"; }
                            @PostMapping("/articles/{id}/edit")
                            public String update(@PathVariable Long id) { return "index"; }
                        }
                        """),
                new SourceCode("src/main/resources/templates/article-form.html", "html", """
                        <form th:action="${articleId != null}
                                ? @{/articles/{id}/edit(id=${articleId})}
                                : @{/articles}" method="post"></form>
                        """));

        List<String> warnings = service.validateProject(codes, contract);

        assertFalse(warnings.stream().anyMatch(value -> value.contains("source file does not issue that request")),
                warnings.toString());
    }

    /**
     * JavaScript 字符串拼接的动态路径应能匹配带路径变量的契约，避免把真实 DELETE 调用判成缺失。
     */
    @Test
    void acceptsConcatenatedDynamicFetchPath() {
        ProjectContract contract = new ProjectContract(
                List.of(new ApiEndpointContract(
                        "DELETE", "/urls/{shortCode}", "", "", "UrlController", "delete")),
                List.of(),
                List.of(new FrontendCallContract(
                        "src/main/resources/templates/metadata.html", "DELETE", "/urls/{shortCode}", "delete")),
                List.of());
        List<SourceCode> codes = List.of(
                new SourceCode("src/main/java/com/example/UrlController.java", "java", """
                        package com.example;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        public class UrlController {
                            @DeleteMapping("/urls/{shortCode}")
                            public void delete(@PathVariable String shortCode) {}
                        }
                        """),
                new SourceCode("src/main/resources/templates/metadata.html", "html", """
                        <form id="deleteForm"><button id="deleteBtn">Delete</button></form>
                        <script>
                        const shortCode = 'ABC123';
                        fetch('/urls/' + shortCode, { method: 'DELETE' });
                        </script>
                        """));

        List<String> warnings = service.validateProject(codes, contract);

        assertFalse(warnings.stream().anyMatch(value -> value.contains("source file does not issue that request")),
                warnings.toString());
    }

    /**
     * 模板声明 th:object 时必须存在同名 MVC 模型属性，否则渲染首页时会直接抛出模板异常。
     */
    @Test
    void detectsMissingThymeleafModelAttribute() {
        List<SourceCode> codes = List.of(
                new SourceCode("src/main/java/com/example/HomeController.java", "java", """
                        package com.example;
                        import org.springframework.stereotype.Controller;
                        import org.springframework.web.bind.annotation.GetMapping;
                        @Controller
                        public class HomeController {
                            @GetMapping("/") public String home() { return "index"; }
                        }
                        """),
                new SourceCode("src/main/resources/templates/index.html", "html",
                        "<form th:object=\"${createUrlRequest}\"></form>"));

        List<String> warnings = service.validateProject(codes, null);

        assertTrue(warnings.stream().anyMatch(value -> value.startsWith("Thymeleaf model attribute `createUrlRequest`")));
    }

    /**
     * 相同方法和动态路径由两个 Controller 重复声明时必须在 Spring 启动前阻断。
     */
    @Test
    void detectsDuplicateControllerMappingsAcrossSlices() {
        List<SourceCode> codes = List.of(
                new SourceCode("src/main/java/com/example/UrlController.java", "java", """
                        package com.example;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        public class UrlController {
                            @GetMapping("/{code}") public void redirect(String code) {}
                        }
                        """),
                new SourceCode("src/main/java/com/example/RedirectController.java", "java", """
                        package com.example;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        public class RedirectController {
                            @GetMapping("/{shortCode}") public void redirect(String shortCode) {}
                        }
                        """));

        List<String> warnings = service.validateProject(codes, null);

        assertTrue(warnings.stream().anyMatch(value -> value.startsWith("Duplicate controller mapping `GET /{}`")));
    }

    /**
     * 组件构造器注入项目内普通类时必须存在组件注解或 @Bean 工厂方法。
     */
    @Test
    void detectsUnregisteredConcreteSpringDependency() {
        List<SourceCode> invalid = List.of(
                new SourceCode("src/main/java/com/example/SnakeGame.java", "java", """
                        package com.example;
                        public class SnakeGame {}
                        """),
                new SourceCode("src/main/java/com/example/SnakeHandler.java", "java", """
                        package com.example;
                        import org.springframework.stereotype.Component;
                        @Component
                        public class SnakeHandler {
                            public SnakeHandler(SnakeGame game) {}
                        }
                        """));
        List<SourceCode> valid = List.of(
                invalid.get(0), invalid.get(1),
                new SourceCode("src/main/java/com/example/GameConfig.java", "java", """
                        package com.example;
                        import org.springframework.context.annotation.*;
                        @Configuration
                        public class GameConfig {
                            @Bean public SnakeGame snakeGame() { return new SnakeGame(); }
                        }
                        """));

        List<String> invalidWarnings = service.validateProject(invalid, null);
        List<String> validWarnings = service.validateProject(valid, null);

        assertTrue(invalidWarnings.stream().anyMatch(value -> value.startsWith("Spring bean dependency `SnakeGame`")));
        assertFalse(validWarnings.stream().anyMatch(value -> value.startsWith("Spring bean dependency `SnakeGame`")));
    }

    /**
     * 已在真实批次中出现过的 Spring API 误用应在编译前被拦截，标准 builder 链不应误报。
     */
    @Test
    void detectsKnownInvalidSpringApisWithoutRejectingValidBuilders() {
        List<SourceCode> invalid = List.of(new SourceCode(
                "src/main/java/com/example/UrlController.java", "java", """
                        package com.example;
                        import java.net.URI;
                        import org.springframework.http.ResponseEntity;
                        import org.springframework.web.util.UriComponentsBuilder;
                        class UrlController {
                            Object redirect(URI uri) { return ResponseEntity.temporaryRedirect(uri).build(); }
                            URI expand() { return UriComponentsBuilder.fromPath("/{code}").toUri(); }
                        }
                        """));
        List<SourceCode> valid = List.of(new SourceCode(
                "src/main/java/com/example/UrlController.java", "java", """
                        package com.example;
                        import java.net.URI;
                        import org.springframework.http.*;
                        import org.springframework.web.util.UriComponentsBuilder;
                        class UrlController {
                            Object redirect(URI uri) {
                                return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT).location(uri).build();
                            }
                            URI expand() {
                                return UriComponentsBuilder.fromPath("/{code}").buildAndExpand("abc").toUri();
                            }
                        }
                        """));

        List<String> invalidWarnings = service.validateProject(invalid, null);
        List<String> validWarnings = service.validateProject(valid, null);

        assertTrue(invalidWarnings.stream().filter(value -> value.startsWith("Invalid Spring API usage")).count() >= 2);
        assertFalse(validWarnings.stream().anyMatch(value -> value.startsWith("Invalid Spring API usage")),
                validWarnings.toString());
    }

    /**
     * 声明为重定向的 GET 端点不能返回 void 丢弃 URL；直接写 HttpServletResponse 时允许通过。
     */
    @Test
    void detectsVoidRedirectEndpointWithoutExplicitHttpResponse() {
        ProjectContract contract = new ProjectContract(
                List.of(new ApiEndpointContract("GET", "/{shortCode}", "", "", "UrlController", "redirect")),
                List.of(), List.of(), List.of());
        SourceCode invalid = new SourceCode("src/main/java/com/example/UrlController.java", "java", """
                package com.example;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class UrlController {
                    @GetMapping("/{shortCode}")
                    public void redirectToOriginal(@PathVariable String shortCode) { service(shortCode); }
                    private void service(String code) {}
                }
                """);
        SourceCode valid = new SourceCode("src/main/java/com/example/UrlController.java", "java", """
                package com.example;
                import jakarta.servlet.http.HttpServletResponse;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class UrlController {
                    @GetMapping("/{shortCode}")
                    public void redirectToOriginal(@PathVariable String shortCode, HttpServletResponse response)
                            throws java.io.IOException {
                        response.sendRedirect("https://example.com");
                    }
                }
                """);

        List<String> invalidWarnings = service.validateProject(List.of(invalid), contract);
        List<String> validWarnings = service.validateProject(List.of(valid), contract);

        assertTrue(invalidWarnings.stream().anyMatch(value -> value.startsWith("Redirect endpoint `GET /{}`")));
        assertFalse(validWarnings.stream().anyMatch(value -> value.startsWith("Redirect endpoint")),
                validWarnings.toString());
    }

    /**
     * 服务层抛出的资源不存在异常必须有 404 映射，避免接口测试观察到 500 或 ServletException。
     */
    @Test
    void detectsMissingNotFoundExceptionMapping() {
        SourceCode serviceFile = new SourceCode("src/main/java/com/example/UrlService.java", "java", """
                package com.example;
                class UrlService {
                    String find(String code) { throw new ShortCodeNotFoundException(code); }
                }
                class ShortCodeNotFoundException extends RuntimeException {
                    ShortCodeNotFoundException(String code) { super(code); }
                }
                """);
        SourceCode mappedException = new SourceCode(
                "src/main/java/com/example/ShortCodeNotFoundException.java", "java", """
                        package com.example;
                        import org.springframework.http.HttpStatus;
                        import org.springframework.web.bind.annotation.ResponseStatus;
                        @ResponseStatus(HttpStatus.NOT_FOUND)
                        class ShortCodeNotFoundException extends RuntimeException {}
                        """);
        SourceCode responseStatusException = new SourceCode(
                "src/main/java/com/example/PollService.java", "java", """
                        package com.example;
                        import org.springframework.http.HttpStatus;
                        import org.springframework.web.server.ResponseStatusException;
                        class PollService {
                            Object find() {
                                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "poll not found");
                            }
                        }
                        """);
        SourceCode lambdaFactory = new SourceCode(
                "src/main/java/com/example/LookupService.java", "java", """
                        package com.example;
                        class LookupService {
                            Object find(java.util.Optional<Object> value) {
                                return value.orElseThrow(() -> new RuntimeException("short code not found"));
                            }
                        }
                        """);

        List<String> invalidWarnings = service.validateProject(List.of(serviceFile), null);
        List<String> lambdaWarnings = service.validateProject(List.of(lambdaFactory), null);
        List<String> validWarnings = service.validateProject(List.of(serviceFile, mappedException), null);
        List<String> explicitStatusWarnings = service.validateProject(List.of(responseStatusException), null);

        assertTrue(invalidWarnings.stream()
                .anyMatch(value -> value.startsWith("Not-found exception mapping missing")));
        assertTrue(lambdaWarnings.stream()
                .anyMatch(value -> value.startsWith("Not-found exception mapping missing")));
        assertFalse(validWarnings.stream()
                .anyMatch(value -> value.startsWith("Not-found exception mapping missing")), validWarnings.toString());
        assertFalse(explicitStatusWarnings.stream()
                .anyMatch(value -> value.startsWith("Not-found exception mapping missing")),
                explicitStatusWarnings.toString());
    }
}
