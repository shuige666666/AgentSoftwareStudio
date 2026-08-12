package com.core.multiAgentSoftwareStudio.Agent;

import com.core.multiAgentSoftwareStudio.Model.Generation.PrdDocument;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;

/**
 * 角色 C: 工程师 (Software Engineer)
 */
public class DeveloperAgent extends AbstractJsonAgent {

    private static final String SYSTEM_PROMPT = """
                        You are a Senior Java Developer. Your task is to implement ONE specific file based on the architecture design.
                        You will receive a Global PRD, the Project Structure (context), and a SPECIFIC FILE TASK.

                        Guidelines:
                        1. Write production-ready, compiling code.
                        2. Include necessary imports and package declarations.
                        3. Do NOT use placeholders like '// TODO: implement logic', write the actual logic.
                        4. Ensure the code aligns with the PRD and the overall Project Structure.
                        5. CRITICAL: Pay attention to the interaction between files. For example:
                           - Controller endpoints MUST match what the Frontend expects.
                           - Service methods MUST match what the Controller calls.
                           - Field names in DTOs/Models MUST be consistent across the project.
                        6. If PROJECT CONTRACT defines endpoints, DTOs, MVC views, or frontend calls related to this file, follow it exactly.
                        7. For frontend files, every DOM id referenced by JavaScript (`getElementById`, `querySelector('#id')`) MUST exist in the generated HTML.
                        8. For interactive screens, wire button/event handlers end-to-end so user actions trigger the intended fetch or UI transition.
                        9. If using visibility classes such as `d-none`, always implement both show and hide transitions consistently.
                        10. For a Spring Boot pom.xml, use Java 17, Spring Boot 3.2.4, and spring-boot-starter-test.
                            Never add `spring-websocket-test` or Spring test annotations from newer incompatible releases.
                        11. The PROJECT STRUCTURE contains only files available to the current delivery slice.
                            Do not import, declare, instantiate, or otherwise reference application types absent from it.
                            Represent relationships to future capabilities with primitive IDs or current-slice DTOs instead.
                        12. Treat ALREADY IMPLEMENTED FILES as immutable APIs. Copy their parameter, return, generic,
                            and nested DTO types exactly. Never approximate a declared DTO type with Map, Object,
                            a raw collection, or a newly invented overload just because it looks structurally similar.
                        13. A local variable captured by a lambda or stream callback MUST be final or effectively final.
                            Compute aggregates with stream terminal operations or copy the completed value into a final snapshot.
                        14. For Thymeleaf frontend calls, implement every contracted method/path in its declared source file.
                            Use standard `@{/path}` or `@{/path/{id}(id=${value})}` URL expressions for links and forms.
                            For create/edit variants, use separate forms or a conditional between complete `@{...}` expressions;
                            never construct th:href/th:action by concatenating multiple `${...}` expressions.
                        15. Every Controller path variable MUST affect the requested lookup or mutation. Pass parent IDs
                            into the Service and validate parent-child ownership when a request also contains a child ID;
                            never accept a path variable and then ignore it.
                        16. For generated Java 17 entities, models, and DTOs, write the required constructors, getters,
                            and setters explicitly. Do not rely on Lombok-generated members for APIs used by other files.
                        17. `ResponseEntity.notFound()` returns a HeadersBuilder and cannot accept a body. Use
                            `ResponseEntity.notFound().build()` for an empty 404, or
                            `ResponseEntity.status(HttpStatus.NOT_FOUND).body(value)` when returning an error body.
                        18. When a Thymeleaf template uses `th:object="${name}"`, every Controller route that returns
                            that form view must provide `name` through Model, ModelAndView, or @ModelAttribute.
                            Use @ModelAttribute for browser form submissions; do not combine a Thymeleaf form with
                            @RequestBody JSON binding unless JavaScript actually sends JSON.
                        19. In an `@Controller`, a void handler can make Spring infer the request path as a view name.
                            For DELETE/POST/PUT/PATCH actions that do not render a view, return ResponseEntity/redirect
                            or annotate the handler with @ResponseBody according to PROJECT CONTRACT.
                        20. Every concrete application class injected into a Spring component constructor must itself be
                            registered with @Service/@Component/@Repository or provided by an existing @Bean method.
                            Plain domain objects should be instantiated by the owning service, not constructor-injected.
                        21. Redirect endpoints must return a real 3xx response with a Location header or a valid MVC
                            redirect view; never throw RuntimeException to imitate a redirect. Keep redirect and
                            metadata/detail routes on distinct method/path mappings without overlapping `produces` rules.
                        22. DTOs and domain values that participate in Jackson request, response, or WebSocket payloads
                            must support deserialization through an explicit @JsonCreator/@JsonProperty constructor or
                            a safe no-arg constructor plus setters. Preserve immutable invariants where possible.
                        23. Implement only capabilities assigned by PROJECT CONTRACT and AVAILABLE SLICE STRUCTURE.
                            Do not add future endpoint methods or reference application types absent from the available
                            structure, even when the full PRD mentions those later capabilities.
                        21. Before adding a Controller endpoint, inspect ALREADY IMPLEMENTED FILES for the same HTTP
                            method and normalized path. Extend the existing endpoint owner; never create a second mapping
                            such as two `GET /{shortCode}` handlers in different Controllers.

                        CRITICAL RULES:
                        1. You must ONLY implement the code for the 'Current Target File'.
                        2. Do NOT implement the whole project. Focus strictly on the single class requested.
                        3. The 'filename' in your JSON output MUST match the 'Current Target File' path exactly.
                        4. Do NOT use markdown code blocks, just return a valid JSON object.
                        5. The `code` field must contain the complete non-blank file. Never return null, an empty string,
                           whitespace, or an explanation without source code.

                        JSON FORMAT RULES:
                        1. The output MUST be a valid JSON object matching the `SourceCode` record.
                        2. The `code` field MUST be a single string. All newlines in the code MUST be escaped as `\\n`.
                        3. Escape any double quotes (`"`) as `\\"` inside the code string.
                        """;

    public DeveloperAgent(ChatLanguageModel model,
                          LangGraphPromptExecutor promptExecutor,
                          ObjectMapper objectMapper) {
        super(model, promptExecutor, objectMapper);
    }

    /**
     * 根据 PRD、项目骨架、接口契约和当前文件蓝图生成单个源码文件
     */
    public SourceCode writeCode(PrdDocument prd,
                                ProjectStructure structure,
                                ProjectContract contract,
                                String existingCode,
                                String fileName,
                                String description,
                                String methods,
                                String batchContext) {
        String userPrompt = """
                === GLOBAL CONTEXT (Reference Only) ===
                %s

                === PROJECT STRUCTURE (Reference Only) ===
                %s

                === PROJECT CONTRACT (Must Follow) ===
                %s

                === CURRENT GENERATION BATCH ===
                %s

                === ALREADY IMPLEMENTED FILES (Reference Only) ===
                %s

                =======================================
                === CURRENT TARGET FILE (IMPLEMENT THIS) ===
                Filename: %s
                Description: %s
                Required Methods: %s
                =======================================
                """.formatted(prd, structure, contract, batchContext, existingCode, fileName, description, methods);
        return askJson(SYSTEM_PROMPT, userPrompt, SourceCode.class);
    }
}
