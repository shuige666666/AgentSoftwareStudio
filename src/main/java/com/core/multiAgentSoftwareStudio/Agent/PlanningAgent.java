package com.core.multiAgentSoftwareStudio.Agent;

import com.core.multiAgentSoftwareStudio.Model.Generation.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Model.Generation.PlanningDesign;
import com.core.multiAgentSoftwareStudio.Model.Generation.PlanningConflictDecision;
import com.core.multiAgentSoftwareStudio.Model.Generation.PrdDocument;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ApiEndpointContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.FrontendCallContract;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 用同一规划角色分两步形成需求与总体设计，缺失关键字段时定向重试一次。
 */
public class PlanningAgent extends AbstractJsonAgent {
    private static final String REQUIREMENT_PROMPT = """
            You are the planning agent for a Java project generator. Preserve every explicitly requested
            user-visible function and technical restriction. Do not drop features to make an MVP smaller.
            Separate required behaviors from optional suggestions; do not add optional features to userStories.
            Return one JSON object with exactly these top-level fields:
            {"projectName":"...","projectGoal":"...",
             "userStories":["B1: user can ...","B2: user can ..."],
             "techStack":["explicit constraint or reasonable platform choice"],
             "businessRules":["..."],"assumptions":["..."],"openQuestions":[]}
            Give each required behavior a stable B1, B2, ... identifier. Describe observable success and
            necessary error behavior in each story. Keep the original request authoritative.
            """;
    private static final String DESIGN_PROMPT = """
            You are the same planning agent. Use BOTH the original request and requirement summary.
            Design the project, domain flow, and public interface in one response. Do not silently remove
            any B-numbered required behavior. Use Java 17 and Spring Boot 3.2.4 for a Spring Boot project.
            Return one JSON object using this complete nested shape:
            {
              "structure": {
                "rootPackage":"com.example.app", "projectType":"SPRING_BOOT",
                "mainClassName":"com.example.app.Application",
                "files":[{"fileName":"Example.java","filePath":"src/main/java/com/example/app/Example.java",
                  "layer":"base|service|controller|frontend","batchName":"business-capability",
                  "functionalityDescription":"...","keyMethods":[],"dependsOn":[]}]},
              "contract": {
                "endpoints":[{"method":"POST","path":"/api/resources","requestDto":"DTO name or JSON",
                  "responseDto":"DTO name or JSON","implementedBy":"Controller class or path",
                  "description":"...","requestFields":{"field":"type and meaning"},
                  "responseFields":{"field":"type and meaning"},
                  "errorResponses":{"404":"when absent"},"behaviorIds":["B1"]}],
                "views":[{"name":"view","templatePath":"src/main/resources/templates/view.html",
                  "returnedBy":"Controller","description":"..."}],
                "frontendCalls":[{"sourceFile":"src/main/resources/static/app.js",
                  "method":"GET","path":"/api/resources","description":"..."}],
                "additionalFiles":[{"fileName":"Extra.java","filePath":"src/main/java/com/example/app/Extra.java",
                  "layer":"base","batchName":"business-capability",
                  "functionalityDescription":"...","keyMethods":[],"dependsOn":[]}]},
              "domainDesign":"domain objects, relationships, state changes, storage and key reasons",
              "behaviorCoverage":{"B1":["POST /api/resources"]}
            }
            Arrays may be empty when inapplicable, but every HTTP endpoint must have explicit method and path.
            Use actual field names in requestFields and responseFields. Include required error semantics.
            behaviorCoverage must cover each B-numbered story with observable endpoint or UI interaction.
            For a project with several independently observable capabilities, assign the same short business
            batchName to files serving each capability (for example article-create-query, comment-submit).
            A capability may span domain, API, UI and tests; do not use technical layer names as batches.
            For a small single-capability project, one business batch is enough. Shared support files may
            use a shared name. Preserve every required B-numbered behavior across the complete plan.
            Do not put test files, pom.xml, or the application bootstrap in structure.files; the platform
            creates the build scaffold before business coding. Keep file paths project-relative.
            """;
    private static final String CONFLICT_PROMPT = """
            You are reviewing a specific conflict between the original user request and the current plan.
            Keep the original request authoritative. Do not change user-specified HTTP methods, paths, fields,
            required behavior or platform acceptance criteria to accommodate the current implementation.
            Routine code bugs and internal class/file choices are not planning conflicts: choose KEEP.
            Choose REVISE only if a planning-derived public contract or design choice contradicts the request.
            Return JSON only: {"decision":"KEEP or REVISE","reason":"concrete evidence and rationale"}.
            """;

    public record ConflictReview(String decision, String reason) { }

    public PlanningAgent(ChatLanguageModel model, LangGraphPromptExecutor promptExecutor, ObjectMapper objectMapper) {
        super(model, promptExecutor, objectMapper);
    }

    /**
     * 保留原始请求并检查必需行为标识；缺失时只修补需求摘要。
     */
    public PrdDocument summarizeRequirements(String originalRequest) {
        PrdDocument first = askJson(REQUIREMENT_PROMPT, originalRequest, PrdDocument.class);
        List<String> missing = requirementProblems(first);
        if (missing.isEmpty()) {
            return first;
        }
        PrdDocument repaired = askJson(REQUIREMENT_PROMPT,
                "Original request:\n" + originalRequest + "\nPrevious summary:\n" + json(first)
                        + "\nRepair only these omissions; preserve all other required behaviors:\n" + missing,
                PrdDocument.class);
        List<String> remaining = requirementProblems(repaired);
        if (!remaining.isEmpty()) {
            throw new IllegalStateException("Planning requirement fields missing: " + remaining);
        }
        return repaired;
    }

    /**
     * 在同一次设计输出中取得结构和契约，并检查每个必需行为有公开实现入口。
     */
    public PlanningDesign design(String originalRequest, PrdDocument requirements) {
        return designWithContext(originalRequest, requirements, "");
    }

    /**
     * 真正的公开约定冲突才修订设计，并保留原始请求明确指定的 HTTP 接口。
     */
    public PlanningConflictDecision reviewConflict(String originalRequest, PrdDocument requirements,
                                                   PlanningDesign current, String evidence) {
        String input = "Original request:\n" + originalRequest
                + "\nRequirements:\n" + json(requirements)
                + "\nCurrent effective design:\n" + json(current)
                + "\nReported conflict evidence:\n" + evidence;
        ConflictReview review = askJson(CONFLICT_PROMPT, input, ConflictReview.class);
        if (review == null || blank(review.decision()) || blank(review.reason())) {
            review = askJson(CONFLICT_PROMPT, input + "\nRepair: decision and reason must be present.",
                    ConflictReview.class);
        }
        if (review == null || blank(review.decision()) || blank(review.reason())) {
            throw new IllegalStateException("Planning conflict review missing decision or reason");
        }
        if (!"REVISE".equalsIgnoreCase(review.decision())) {
            return new PlanningConflictDecision(false, review.reason(), null);
        }
        PlanningDesign revised = designWithContext(originalRequest, requirements,
                "Current design:\n" + json(current) + "\nConfirmed planning conflict:\n" + evidence
                        + "\nRevision reason:\n" + review.reason()
                        + "\nRevise only the conflicting planning choice; retain all required behavior.");
        if (!preservesExplicitHttpInterfaces(originalRequest, revised)) {
            return new PlanningConflictDecision(false,
                    "Revision rejected because it changed an HTTP interface explicitly fixed by the user. "
                            + review.reason(), null);
        }
        return new PlanningConflictDecision(true, review.reason(), revised);
    }

    private PlanningDesign designWithContext(String originalRequest, PrdDocument requirements,
                                             String additionalContext) {
        String input = "Original request:\n" + originalRequest + "\nRequirement summary:\n" + json(requirements)
                + (additionalContext.isBlank() ? "" : "\nConflict review context:\n" + additionalContext);
        PlanningDesign first = normalize(askJson(DESIGN_PROMPT, input, PlanningDesign.class), requirements);
        List<String> missing = designProblems(first, requirements);
        if (missing.isEmpty()) {
            return first;
        }
        PlanningDesign repaired = normalize(askJson(DESIGN_PROMPT,
                input + "\nPrevious design:\n" + json(first)
                        + "\nRepair only these design omissions:\n" + missing,
                PlanningDesign.class), requirements);
        List<String> remaining = designProblems(repaired, requirements);
        if (!remaining.isEmpty()) {
            throw new IllegalStateException("Planning design fields missing: " + remaining);
        }
        return repaired;
    }

    private boolean preservesExplicitHttpInterfaces(String originalRequest, PlanningDesign design) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)\\b(GET|POST|PUT|PATCH|DELETE)\\s+(/[^\\s，。；,;]*)")
                .matcher(originalRequest == null ? "" : originalRequest);
        while (matcher.find()) {
            String method = matcher.group(1).toUpperCase();
            String path = matcher.group(2);
            boolean preserved = design.contract().endpoints().stream()
                    .anyMatch(endpoint -> method.equals(endpoint.method()) && path.equals(endpoint.path()));
            if (!preserved) return false;
        }
        return true;
    }

    static List<String> requirementProblems(PrdDocument prd) {
        List<String> missing = new ArrayList<>();
        if (prd == null || prd.projectName() == null || prd.projectName().isBlank()) missing.add("projectName");
        if (prd == null || prd.projectGoal() == null || prd.projectGoal().isBlank()) missing.add("projectGoal");
        if (prd == null || prd.userStories().isEmpty()) {
            missing.add("userStories");
        } else {
            Set<String> ids = new HashSet<>();
            for (String story : prd.userStories()) {
                String id = behaviorId(story);
                if (id == null || !ids.add(id)) missing.add("unique B-numbered userStories");
            }
        }
        return missing;
    }

    static List<String> designProblems(PlanningDesign design, PrdDocument requirements) {
        List<String> missing = new ArrayList<>();
        if (design == null || design.structure() == null) return List.of("structure");
        ProjectStructure structure = design.structure();
        if (blank(structure.rootPackage())) missing.add("structure.rootPackage");
        if (blank(structure.projectType())) missing.add("structure.projectType");
        if (blank(structure.mainClassName())) missing.add("structure.mainClassName");
        for (FileBlueprint file : structure.files()) {
            if (file == null || blank(file.filePath())) missing.add("structure.files.filePath");
        }
        if (design.contract() == null) {
            missing.add("contract");
        } else {
            if (design.contract().endpoints().isEmpty() && design.contract().views().isEmpty()
                    && design.contract().frontendCalls().isEmpty()) missing.add("public interface or interaction");
            for (ApiEndpointContract endpoint : design.contract().endpoints()) {
                if (endpoint == null || blank(endpoint.method()) || blank(endpoint.path())) {
                    missing.add("contract.endpoints.method/path");
                }
            }
            for (FrontendCallContract call : design.contract().frontendCalls()) {
                if (call == null || blank(call.method()) || blank(call.path())) {
                    missing.add("contract.frontendCalls.method/path");
                }
            }
        }
        if (blank(design.domainDesign())) missing.add("domainDesign");
        if (requirements != null) {
            for (String story : requirements.userStories()) {
                String id = behaviorId(story);
                if (id != null && (design.behaviorCoverage().get(id) == null
                        || design.behaviorCoverage().get(id).isEmpty())) missing.add("behaviorCoverage." + id);
            }
        }
        return missing;
    }

    private PlanningDesign normalize(PlanningDesign design, PrdDocument requirements) {
        if (design == null || design.structure() == null) return design;
        ProjectStructure structure = design.structure();
        String type = structure.projectType();
        if (blank(type) && requirements != null && requirements.techStack().stream()
                .anyMatch(stack -> stack.toLowerCase().contains("spring boot"))) type = "SPRING_BOOT";
        String main = structure.mainClassName();
        if (blank(main) && !blank(structure.rootPackage())) {
            List<String> candidates = structure.files().stream().filter(java.util.Objects::nonNull)
                    .map(FileBlueprint::targetPath).filter(path -> path.endsWith("Application.java"))
                    .map(path -> structure.rootPackage() + "." + path.substring(path.lastIndexOf('/') + 1,
                            path.length() - ".java".length())).distinct().toList();
            if (candidates.size() == 1) main = candidates.getFirst();
        }
        return new PlanningDesign(new ProjectStructure(structure.rootPackage(), type, main, structure.files()),
                design.contract(), design.domainDesign(), design.behaviorCoverage());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize planning context", e);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String behaviorId(String story) {
        if (story == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^(B[1-9][0-9]*):\\s+.+")
                .matcher(story.trim());
        return matcher.matches() ? matcher.group(1) : null;
    }
}
