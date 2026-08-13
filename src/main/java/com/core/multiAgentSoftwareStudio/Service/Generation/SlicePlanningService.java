package com.core.multiAgentSoftwareStudio.Service.Generation;

import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ApiEndpointContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.FrontendCallContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ViewContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.DeliverySlice;
import com.core.multiAgentSoftwareStudio.Model.Generation.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Generation.SliceDeliveryPlan;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * 将架构蓝图中的业务 batchName 转换为可独立验收的垂直切片计划。
 */
@Service
public class SlicePlanningService {

    private static final String FRONTEND_INTEGRATION_SLICE = "frontend-integration";

    private static final Set<String> GENERIC_BATCH_NAMES = Set.of(
            "application", "applications", "base", "model", "models", "entity", "entities",
            "dto", "dtos", "config", "configuration", "configurations",
            "repository", "repositories", "service", "services", "controller", "controllers",
            "frontend", "frontends", "view", "views", "template", "templates", "test", "tests",
            "layer", "layers", "web", "api", "domain", "persistence", "and", "of");

    private final SourceCodePathService sourceCodePathService;

    public SlicePlanningService(SourceCodePathService sourceCodePathService) {
        this.sourceCodePathService = sourceCodePathService;
    }

    /**
     * 优先采用架构师给出的业务切片名；缺失或仍按技术层命名时安全退化为一个完整功能切片。
     */
    public SliceDeliveryPlan createPlan(ProjectStructure structure, ProjectContract contract) {
        List<FileBlueprint> files = structure == null || structure.files() == null
                ? List.of()
                : structure.files();
        if (files.isEmpty()) {
            return SliceDeliveryPlan.empty();
        }

        List<FileBlueprint> sharedBlueprints = files.stream().filter(this::isSharedBootstrapFile).toList();
        Set<String> sharedPaths = sharedBlueprints.stream()
                .map(FileBlueprint::targetPath)
                .map(sourceCodePathService::normalizePath)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Map<String, List<FileBlueprint>> grouped = new LinkedHashMap<>();
        for (FileBlueprint file : files) {
            if (sharedPaths.contains(sourceCodePathService.normalizePath(file.targetPath()))) {
                continue;
            }
            String group = effectiveSliceName(file.batchName());
            grouped.computeIfAbsent(group, ignored -> new ArrayList<>()).add(file);
        }
        if (grouped.isEmpty()) {
            grouped.put("project-feature", new ArrayList<>());
        }

        // 同一个 Controller 若承载多个业务能力，单文件无法安全归属多个切片，保守合并这些后端能力。
        mergeControllerCoupledSlices(grouped, contract);

        // 蓝图显式依赖、方法描述或契约类型能够确定唯一消费者时，基础支持文件应随消费者交付，
        // 防止 Architect 漏写 dependsOn 后重新产生未来类型引用。
        coLocateSupportDependencies(grouped, contract);

        // 综合前端通常同时调用多个后端能力，放到最后的集成切片，避免首片提前承诺未来接口。
        moveFrontendFilesToIntegrationSlice(grouped);

        Map<String, String> ownerByPath = new LinkedHashMap<>();
        grouped.forEach((sliceName, sliceFiles) -> sliceFiles.forEach(file ->
                ownerByPath.put(sourceCodePathService.normalizePath(file.targetPath()), sliceName)));
        Set<String> knownBlueprintTypes = files.stream()
                .map(FileBlueprint::targetPath)
                .map(sourceCodePathService::normalizePath)
                .filter(path -> path.endsWith(".java"))
                .map(path -> Path.of(path).getFileName().toString().replaceFirst("\\.java$", ""))
                .collect(java.util.stream.Collectors.toSet());

        Map<String, Set<String>> dependenciesBySlice = collectSliceDependencies(grouped, ownerByPath, contract);
        List<String> orderedNames = sortByDependencies(dependenciesBySlice);
        Map<String, String> idByName = uniqueSliceIds(orderedNames);
        List<DeliverySlice> slices = new ArrayList<>();
        for (int index = 0; index < orderedNames.size(); index++) {
            String sliceName = orderedNames.get(index);
            List<FileBlueprint> sliceFiles = new ArrayList<>();
            if (index == 0) {
                sliceFiles.addAll(sharedBlueprints);
            }
            sliceFiles.addAll(grouped.getOrDefault(sliceName, List.of()));
            ProjectContract sliceContract = sliceContract(contract, sliceName, sliceFiles, knownBlueprintTypes);
            List<String> criteria = acceptanceCriteria(sliceContract, sliceFiles);
            List<String> dependencies = List.copyOf(dependenciesBySlice.getOrDefault(sliceName, Set.of()));
            slices.add(new DeliverySlice(
                    idByName.get(sliceName), sliceName, criteria, sliceFiles, sliceContract,
                    dependencies.stream().map(idByName::get).toList(), List.copyOf(sharedPaths)));
        }
        return new SliceDeliveryPlan(slices, List.copyOf(sharedPaths), SliceDeliveryPlan.CURRENT_POLICY_VERSION);
    }

    /**
     * 根据显式依赖、蓝图类型引用和接口契约，将唯一消费者使用的基础 Java 文件并入业务切片。
     */
    private void coLocateSupportDependencies(
            Map<String, List<FileBlueprint>> grouped,
            ProjectContract contract) {
        Map<String, FileBlueprint> blueprintByPath = new LinkedHashMap<>();
        Map<String, String> ownerByPath = new LinkedHashMap<>();
        grouped.forEach((sliceName, files) -> files.forEach(file -> {
            String path = sourceCodePathService.normalizePath(file.targetPath());
            blueprintByPath.put(path, file);
            ownerByPath.put(path, sliceName);
        }));

        Map<String, Set<String>> consumersByDependency = new LinkedHashMap<>();
        grouped.forEach((sliceName, files) -> files.stream()
                .filter(file -> sourceCodePathService.normalizePath(file.targetPath()).endsWith(".java"))
                .flatMap(file -> file.dependsOn().stream())
                .map(sourceCodePathService::normalizePath)
                .forEach(path -> consumersByDependency
                        .computeIfAbsent(path, ignored -> new LinkedHashSet<>()).add(sliceName)));

        // 1. 从蓝图功能描述和 keyMethods 中补全简单类型引用；这里只移动基础叶子类型。
        Map<String, String> supportPathByType = new LinkedHashMap<>();
        blueprintByPath.forEach((path, blueprint) -> {
            if (isSupportJavaFile(blueprint)) {
                supportPathByType.put(simpleTypeNameFromPath(path), path);
            }
        });
        grouped.forEach((sliceName, files) -> files.stream()
                .filter(file -> !isSupportJavaFile(file))
                .forEach(file -> {
                    String blueprintText = file.functionalityDescription() + "\n"
                            + String.join("\n", file.keyMethods());
                    supportPathByType.forEach((typeName, path) -> {
                        if (containsTypeReference(blueprintText, typeName)) {
                            consumersByDependency.computeIfAbsent(path, ignored -> new LinkedHashSet<>())
                                    .add(sliceName);
                        }
                    });
                }));

        // 2. 契约中的 request/response DTO 归属于端点实现切片，补足 Architect 遗漏的 dependsOn。
        Map<String, String> ownerByType = new LinkedHashMap<>();
        grouped.forEach((sliceName, files) -> files.forEach(file ->
                ownerByType.put(simpleTypeNameFromPath(file.targetPath()), sliceName)));
        if (contract != null) {
            for (ApiEndpointContract endpoint : contract.endpoints()) {
                String consumer = resolveEndpointSlice(endpoint, ownerByType, grouped.keySet());
                if (consumer == null) {
                    continue;
                }
                for (String contractType : List.of(endpoint.requestDto(), endpoint.responseDto())) {
                    String path = supportPathByType.get(simpleTypeName(contractType));
                    if (path != null) {
                        consumersByDependency.computeIfAbsent(path, ignored -> new LinkedHashSet<>()).add(consumer);
                    }
                }
            }
        }

        for (Map.Entry<String, Set<String>> entry : consumersByDependency.entrySet()) {
            FileBlueprint dependency = blueprintByPath.get(entry.getKey());
            String oldOwner = ownerByPath.get(entry.getKey());
            if (dependency == null || oldOwner == null || entry.getValue().size() != 1
                    || !isSupportJavaFile(dependency)) {
                continue;
            }
            String consumer = entry.getValue().iterator().next();
            if (consumer.equals(oldOwner)) {
                continue;
            }
            grouped.get(oldOwner).remove(dependency);
            grouped.get(consumer).add(dependency);
            ownerByPath.put(entry.getKey(), consumer);
        }
        grouped.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    private boolean containsTypeReference(String text, String typeName) {
        if (text == null || text.isBlank() || typeName == null || typeName.isBlank()) {
            return false;
        }
        return java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(typeName) + "\\b")
                .matcher(text).find();
    }

    private String simpleTypeNameFromPath(String path) {
        String normalized = sourceCodePathService.normalizePath(path);
        String filename = Path.of(normalized).getFileName().toString();
        return filename.replaceFirst("\\.[^.]+$", "");
    }

    /**
     * 仅基础 DTO、Model、Entity、异常等叶子类型可以随唯一消费者移动，禁止提前拉入业务组件。
     */
    private boolean isSupportJavaFile(FileBlueprint file) {
        String path = sourceCodePathService.normalizePath(file.targetPath()).toLowerCase(Locale.ROOT);
        String layer = file.effectiveLayer();
        boolean leafLayer = "base".equals(layer) || "dto".equals(layer) || "model".equals(layer)
                || "entity".equals(layer) || "exception".equals(layer);
        return path.endsWith(".java")
                && leafLayer
                && !path.endsWith("application.java");
    }

    private ProjectContract sliceContract(
            ProjectContract contract,
            String sliceName,
            List<FileBlueprint> files,
            Set<String> knownBlueprintTypes) {
        if (contract == null) {
            return new ProjectContract(List.of(), List.of(), List.of(), List.of());
        }
        Set<String> paths = files.stream().map(FileBlueprint::targetPath)
                .map(sourceCodePathService::normalizePath)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> simpleTypes = files.stream()
                .map(file -> Path.of(sourceCodePathService.normalizePath(file.targetPath())).getFileName().toString())
                .map(name -> name.replaceFirst("\\.[^.]+$", ""))
                .collect(java.util.stream.Collectors.toSet());

        List<ApiEndpointContract> endpoints = contract.endpoints().stream()
                .filter(endpoint -> endpointBelongsToSlice(
                        endpoint, sliceName, simpleTypes, knownBlueprintTypes))
                .filter(endpoint -> endpointMatchesCapability(endpoint, sliceName))
                .toList();
        List<ViewContract> views = contract.views().stream()
                .filter(view -> paths.contains(sourceCodePathService.normalizePath(view.templatePath()))
                        || simpleTypes.contains(simpleTypeName(view.returnedBy())))
                .toList();
        List<FrontendCallContract> calls = contract.frontendCalls().stream()
                .filter(call -> paths.contains(sourceCodePathService.normalizePath(call.sourceFile())))
                // 服务端模板只接收当前能力的交互契约，避免评论切片提前生成文章删除等未来入口。
                .filter(call -> FRONTEND_INTEGRATION_SLICE.equalsIgnoreCase(sliceName)
                        || endpoints.stream().anyMatch(endpoint -> endpoint.method().equalsIgnoreCase(call.method())
                                && canonicalEndpoint(endpoint.path()).equals(canonicalEndpoint(call.path()))))
                .toList();
        return new ProjectContract(endpoints, views, calls, List.of());
    }

    /**
     * 将缺少或写错 Controller 归属的端点按业务语义分配，避免后续切片在无接口契约时被误验收。
     */
    private boolean endpointBelongsToSlice(
            ApiEndpointContract endpoint,
            String sliceName,
            Set<String> simpleTypes,
            Set<String> knownBlueprintTypes) {
        String owner = simpleTypeName(endpoint.implementedBy());
        if (!owner.isBlank() && knownBlueprintTypes.contains(owner)) {
            return simpleTypes.contains(owner);
        }
        return endpointMatchesCapability(endpoint, sliceName);
    }

    private List<String> acceptanceCriteria(ProjectContract contract, List<FileBlueprint> files) {
        List<String> criteria = new ArrayList<>();
        contract.endpoints().forEach(endpoint -> criteria.add(
                endpoint.method() + " " + endpoint.path() + " is implemented and tested."));
        contract.views().forEach(view -> criteria.add(
                "View " + view.name() + " resolves template " + view.templatePath() + "."));
        contract.frontendCalls().forEach(call -> criteria.add(
                call.sourceFile() + " issues " + call.method() + " " + call.path() + "."));
        if (criteria.isEmpty()) {
            criteria.add("Generate and test the declared files: "
                    + files.stream().map(FileBlueprint::targetPath).toList());
        }
        return List.copyOf(criteria);
    }

    private List<String> sortByDependencies(Map<String, Set<String>> sourceDependencies) {
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        sourceDependencies.forEach((name, required) -> dependencies.put(name, new LinkedHashSet<>(required)));
        Queue<String> ready = new ArrayDeque<>();
        dependencies.entrySet().stream().filter(entry -> entry.getValue().isEmpty())
                .map(Map.Entry::getKey).sorted().forEach(ready::offer);
        List<String> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String current = ready.poll();
            if (ordered.contains(current)) {
                continue;
            }
            ordered.add(current);
            dependencies.forEach((name, required) -> {
                required.remove(current);
                if (required.isEmpty() && !ordered.contains(name) && !ready.contains(name)) {
                    ready.offer(name);
                }
            });
        }
        sourceDependencies.keySet().stream().filter(name -> !ordered.contains(name)).sorted().forEach(ordered::add);
        return ordered;
    }

    /**
     * 合并蓝图显式依赖、REST 父资源依赖和前端集成依赖，形成稳定的切片拓扑。
     */
    private Map<String, Set<String>> collectSliceDependencies(
            Map<String, List<FileBlueprint>> grouped,
            Map<String, String> ownerByPath,
            ProjectContract contract) {
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        grouped.keySet().forEach(name -> dependencies.put(name, new LinkedHashSet<>()));
        grouped.forEach((sliceName, sliceFiles) -> sliceFiles.forEach(file -> {
            for (String dependency : file.dependsOn()) {
                String owner = ownerByPath.get(sourceCodePathService.normalizePath(dependency));
                if (owner != null && !owner.equals(sliceName)) {
                    dependencies.get(sliceName).add(owner);
                }
            }
        }));

        Map<String, String> ownerByType = new LinkedHashMap<>();
        grouped.forEach((sliceName, sliceFiles) -> sliceFiles.forEach(file -> {
            String path = sourceCodePathService.normalizePath(file.targetPath());
            if (path.endsWith(".java")) {
                ownerByType.put(Path.of(path).getFileName().toString().replaceFirst("\\.java$", ""), sliceName);
            }
        }));
        if (contract != null) {
            for (ApiEndpointContract child : contract.endpoints()) {
                String childOwner = resolveEndpointSlice(child, ownerByType, grouped.keySet());
                if (childOwner == null) {
                    continue;
                }
                for (ApiEndpointContract parent : contract.endpoints()) {
                    String parentOwner = resolveEndpointSlice(parent, ownerByType, grouped.keySet());
                    if (parentOwner != null && !parentOwner.equals(childOwner)
                            && isParentEndpoint(parent.path(), child.path())) {
                        dependencies.get(childOwner).add(parentOwner);
                    }
                }
            }
        }

        // 架构蓝图偶尔会把核心资源反向依赖到评论、投票等附属能力，先消除这种逆序边，
        // 否则后续能力推断会构成环并让拓扑排序退化为字母顺序。
        removeContradictoryCapabilityDependencies(dependencies);
        inferCapabilityDependencies(dependencies);

        if (dependencies.containsKey(FRONTEND_INTEGRATION_SLICE)) {
            dependencies.keySet().stream()
                    .filter(name -> !FRONTEND_INTEGRATION_SLICE.equals(name))
                    .forEach(dependencies.get(FRONTEND_INTEGRATION_SLICE)::add);
        }
        return dependencies;
    }

    /**
     * 优先采用真实 Controller 归属；契约归属缺失或臆造时按唯一业务能力回退，保住父子资源顺序。
     */
    private String resolveEndpointSlice(
            ApiEndpointContract endpoint,
            Map<String, String> ownerByType,
            Set<String> sliceNames) {
        String declaredOwner = ownerByType.get(simpleTypeName(endpoint.implementedBy()));
        if (declaredOwner != null) {
            return declaredOwner;
        }
        List<String> semanticOwners = sliceNames.stream()
                .filter(name -> !FRONTEND_INTEGRATION_SLICE.equals(name))
                .filter(name -> endpointMatchesCapability(endpoint, name))
                .toList();
        return semanticOwners.size() == 1 ? semanticOwners.getFirst() : null;
    }

    /**
     * 删除与稳定能力阶段相反的显式依赖，防止核心 CRUD 与附属能力之间形成依赖环。
     */
    private void removeContradictoryCapabilityDependencies(Map<String, Set<String>> dependencies) {
        dependencies.forEach((child, required) -> {
            if (!hasRecognizedCapabilityMarker(child)) {
                return;
            }
            int childRank = capabilityRank(child);
            required.removeIf(parent -> hasRecognizedCapabilityMarker(parent)
                    && capabilityRank(parent) > childRank);
        });
    }

    /**
     * 当模型遗漏 dependsOn 或 implementedBy 时，用同领域能力阶段做保守排序。
     */
    private void inferCapabilityDependencies(Map<String, Set<String>> dependencies) {
        List<String> names = dependencies.keySet().stream()
                .filter(name -> !FRONTEND_INTEGRATION_SLICE.equals(name))
                .toList();
        for (String child : names) {
            for (String parent : names) {
                if (child.equals(parent) || !sameCapabilityDomain(child, parent)) {
                    continue;
                }
                int childRank = capabilityRank(child);
                int parentRank = capabilityRank(parent);
                if (parentRank < childRank && !dependencies.get(parent).contains(child)) {
                    dependencies.get(child).add(parent);
                }
            }
        }

        // 模型可能把同一领域写成 article-*、blog-* 等不同前缀；附属能力若仍无前驱，
        // 保守依赖所有更基础能力，宁可延后交付也不让评论、投票先于主体资源生成。
        for (String child : names) {
            int childRank = capabilityRank(child);
            boolean hasLowerRankDependency = dependencies.get(child).stream()
                    .anyMatch(parent -> capabilityRank(parent) < childRank);
            if (hasLowerRankDependency) {
                continue;
            }
            names.stream()
                    .filter(parent -> !parent.equals(child))
                    .filter(parent -> capabilityRank(parent) < childRank)
                    .forEach(dependencies.get(child)::add);
        }
    }

    /**
     * 识别一个 Controller 同时实现创建、列表、投票等多个能力的情况，并合并为可编译后端切片。
     */
    private void mergeControllerCoupledSlices(
            Map<String, List<FileBlueprint>> grouped,
            ProjectContract contract) {
        if (contract == null || grouped.size() <= 1) {
            return;
        }
        Map<String, String> controllerOwner = new LinkedHashMap<>();
        grouped.forEach((sliceName, sliceFiles) -> sliceFiles.stream()
                .filter(file -> "controller".equals(file.effectiveLayer()))
                .forEach(file -> controllerOwner.put(
                        Path.of(sourceCodePathService.normalizePath(file.targetPath()))
                                .getFileName().toString().replaceFirst("\\.java$", ""),
                        sliceName)));

        for (Map.Entry<String, String> controller : controllerOwner.entrySet()) {
            if (!grouped.containsKey(controller.getValue())) {
                continue;
            }
            boolean onlyDeclaredController = controllerOwner.size() == 1;
            List<ApiEndpointContract> implementedEndpoints = contract.endpoints().stream()
                    .filter(endpoint -> {
                        String declaredOwner = simpleTypeName(endpoint.implementedBy());
                        if (controller.getKey().equals(declaredOwner)) {
                            return true;
                        }
                        // 契约偶尔会遗漏或臆造 Controller 名；项目只有一个真实 Controller 时，
                        // 将相关端点归并给它，避免后续切片无法扩展已经验收的共享入口。
                        return onlyDeclaredController && !controllerOwner.containsKey(declaredOwner);
                    })
                    .toList();
            if (implementedEndpoints.size() <= 1) {
                continue;
            }
            LinkedHashSet<String> coupled = new LinkedHashSet<>();
            coupled.add(controller.getValue());
            grouped.keySet().stream()
                    .filter(this::hasRecognizedCapabilityMarker)
                    .filter(sliceName -> implementedEndpoints.stream()
                            .anyMatch(endpoint -> endpointMatchesCapability(endpoint, sliceName)))
                    .forEach(coupled::add);
            if (coupled.size() <= 1) {
                continue;
            }
            String domain = capabilityDomain(controller.getValue());
            String mergedName = (domain.isBlank() ? "project" : domain) + "-backend";
            List<FileBlueprint> mergedFiles = new ArrayList<>();
            coupled.forEach(sliceName -> mergedFiles.addAll(grouped.getOrDefault(sliceName, List.of())));
            coupled.forEach(grouped::remove);
            grouped.computeIfAbsent(mergedName, ignored -> new ArrayList<>()).addAll(mergedFiles);
        }
    }

    private boolean hasRecognizedCapabilityMarker(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return normalized.matches(".*(?:creation|create|publishing|publish|management|crud|core|"
                + "listing|list|query|search|detail|details|comment|comments|voting|vote|result|results).*");
    }

    /**
     * 同一 Controller 承载多个业务能力时按方法、路径和 DTO 语义切分端点，避免首片承诺未来接口。
     */
    private boolean endpointMatchesCapability(ApiEndpointContract endpoint, String sliceName) {
        String capability = sliceName == null ? "" : sliceName.toLowerCase(Locale.ROOT);
        String path = canonicalEndpoint(endpoint.path()).toLowerCase(Locale.ROOT);
        // 响应 DTO 只能作为结果形态，不能决定动作归属；例如 VoteResultResponse 仍属于投票切片。
        String routingEvidence = (endpoint.method() + " " + path + " " + endpoint.requestDto() + " "
                + endpoint.description()).toLowerCase(Locale.ROOT);
        String responseEvidence = endpoint.responseDto() == null
                ? ""
                : endpoint.responseDto().toLowerCase(Locale.ROOT);
        boolean itemPath = path.contains("{}");
        boolean voteAction = routingEvidence.matches(".*(?:vote|voting).*" );
        boolean resultAction = routingEvidence.matches(".*(?:result|results).*" )
                || !voteAction && "GET".equals(endpoint.method()) && responseEvidence.contains("result");
        boolean commentAction = routingEvidence.matches(".*(?:comment|comments).*" );
        boolean adjunctEndpoint = commentAction || voteAction || resultAction;

        if (capability.matches(".*(?:comment|comments).*$")) {
            return commentAction;
        }
        if (capability.matches(".*(?:voting|vote).*$")) {
            return voteAction;
        }
        if (capability.matches(".*(?:result|results).*$")) {
            return resultAction;
        }
        if (capability.matches(".*(?:listing|list|query|search).*$")) {
            return routingEvidence.matches(".*(?:list|all|summary|query|search).*")
                    || "GET".equals(endpoint.method()) && !itemPath && !adjunctEndpoint;
        }
        if (capability.matches(".*(?:detail|details).*$")) {
            return routingEvidence.contains("detail")
                    || "GET".equals(endpoint.method()) && itemPath && !adjunctEndpoint;
        }
        if (capability.matches(".*(?:creation|create).*$")) {
            return routingEvidence.matches(".*(?:create|creation).*")
                    || "POST".equals(endpoint.method()) && !itemPath && !adjunctEndpoint;
        }
        if (capability.matches(".*(?:publishing|publish|management|crud|core).*$")) {
            return !adjunctEndpoint;
        }
        return true;
    }

    private boolean sameCapabilityDomain(String left, String right) {
        return capabilityDomain(left).equals(capabilityDomain(right));
    }

    private String capabilityDomain(String value) {
        String[] tokens = value == null ? new String[0] : value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        if (tokens.length == 0 || tokens[0].isBlank()) {
            return "";
        }
        return tokens[0].endsWith("s") && tokens[0].length() > 1
                ? tokens[0].substring(0, tokens[0].length() - 1)
                : tokens[0];
    }

    private int capabilityRank(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (normalized.matches(".*(?:creation|create|publishing|publish|management|crud|core).*")) {
            return 0;
        }
        if (normalized.matches(".*(?:listing|list|query|search|detail|details).*")) {
            return 1;
        }
        if (normalized.matches(".*(?:comment|comments|voting|vote|result|results).*")) {
            return 2;
        }
        return 1;
    }

    /**
     * 多切片项目中的 HTML、CSS、JS 统一延后，确保它看到的后端契约已经全部交付。
     */
    private void moveFrontendFilesToIntegrationSlice(Map<String, List<FileBlueprint>> grouped) {
        List<FileBlueprint> frontendFiles = grouped.values().stream()
                .flatMap(List::stream)
                .filter(this::isCrossCapabilityFrontendFile)
                .toList();
        long backendFileCount = grouped.values().stream()
                .flatMap(List::stream)
                .filter(file -> !isCrossCapabilityFrontendFile(file))
                .count();
        if (frontendFiles.isEmpty() || backendFileCount == 0) {
            return;
        }
        grouped.values().forEach(files -> files.removeAll(frontendFiles));
        grouped.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        grouped.computeIfAbsent(FRONTEND_INTEGRATION_SLICE, ignored -> new ArrayList<>()).addAll(frontendFiles);
    }

    /**
     * 静态 SPA 资源或显式集成批次延后；Thymeleaf 模板继续随所属业务切片交付。
     */
    private boolean isCrossCapabilityFrontendFile(FileBlueprint file) {
        if (!"frontend".equals(file.effectiveLayer())) {
            return false;
        }
        String path = sourceCodePathService.normalizePath(file.targetPath()).toLowerCase(Locale.ROOT);
        return FRONTEND_INTEGRATION_SLICE.equalsIgnoreCase(file.batchName())
                || path.contains("/static/")
                || path.endsWith(".js")
                || path.endsWith(".css");
    }

    private boolean isParentEndpoint(String parentPath, String childPath) {
        String parent = canonicalEndpoint(parentPath);
        String child = canonicalEndpoint(childPath);
        return !parent.isBlank() && !"/".equals(parent) && child.startsWith(parent + "/");
    }

    private boolean isSharedBootstrapFile(FileBlueprint file) {
        String path = sourceCodePathService.normalizePath(file.targetPath()).toLowerCase(Locale.ROOT);
        String name = Path.of(path).getFileName().toString();
        return "pom.xml".equals(name)
                || name.endsWith("application.java")
                || path.contains("/config/")
                || path.endsWith("application.properties")
                || path.endsWith("application.yml")
                || path.endsWith("application.yaml");
    }

    private String effectiveSliceName(String batchName) {
        String normalized = batchName == null ? "" : batchName.trim();
        if (normalized.isBlank() || isTechnicalBatchName(normalized)) {
            return "project-feature";
        }
        return normalized;
    }

    /**
     * 识别单复数及“Controller Layer”一类纯技术分层名称，避免误当成业务切片。
     */
    private boolean isTechnicalBatchName(String batchName) {
        String[] tokens = batchName.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        List<String> meaningful = java.util.Arrays.stream(tokens).filter(token -> !token.isBlank()).toList();
        return !meaningful.isEmpty() && meaningful.stream().allMatch(GENERIC_BATCH_NAMES::contains);
    }

    private String simpleTypeName(String value) {
        String safe = value == null ? "" : value.trim();
        int separator = Math.max(safe.lastIndexOf('.'), safe.lastIndexOf('$'));
        return separator >= 0 ? safe.substring(separator + 1) : safe;
    }

    private String canonicalEndpoint(String value) {
        String safe = value == null || value.isBlank() ? "/" : value.trim();
        return safe.replaceAll("\\$\\{[^}]+}", "{}")
                .replaceAll("\\{[^}]+}", "{}")
                .replaceAll("/{2,}", "/")
                .replaceFirst("/$", "");
    }

    private String slug(String value) {
        String slug = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return slug.isBlank() ? "project-feature" : slug;
    }

    /**
     * 为中文名称或规范化后重名的切片生成稳定且唯一的 ID，避免预算和 Journal 相互串线。
     */
    private Map<String, String> uniqueSliceIds(List<String> orderedNames) {
        Map<String, String> ids = new LinkedHashMap<>();
        Set<String> used = new LinkedHashSet<>();
        for (String name : orderedNames) {
            String base = slug(name);
            String candidate = base;
            int suffix = 2;
            while (!used.add(candidate)) {
                candidate = base + "-" + suffix++;
            }
            ids.put(name, candidate);
        }
        return ids;
    }
}
