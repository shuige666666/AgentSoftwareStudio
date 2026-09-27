package com.core.multiAgentSoftwareStudio.Service.Generation;

import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ApiEndpointContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.FrontendCallContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ViewContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.DeliverySlice;
import com.core.multiAgentSoftwareStudio.Model.Generation.SliceDeliveryPlan;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把过大的资源能力分成先创建查询、后修改动作，文件清单只作上下文而非任务边界。 */
@Service
public class BusinessMilestoneRefiner {

    /** 仅当两个阶段都有明确的多个接口时才拆分，避免小项目增加验收次数。 */
    public SliceDeliveryPlan refine(SliceDeliveryPlan plan) {
        if (plan == null || plan.slices().isEmpty()) return plan;
        Map<String, String> lastIdByOriginal = new LinkedHashMap<>();
        List<DeliverySlice> result = new ArrayList<>();
        for (DeliverySlice slice : plan.slices()) {
            List<ApiEndpointContract> core = slice.contract().endpoints().stream()
                    .filter(endpoint -> !laterAction(endpoint)).toList();
            List<ApiEndpointContract> later = slice.contract().endpoints().stream()
                    .filter(this::laterAction).toList();
            List<String> dependencies = slice.dependsOnSlices().stream()
                    .map(id -> lastIdByOriginal.getOrDefault(id, id)).toList();
            ViewPartition views = partitionViews(slice.contract(), core, later);
            if (core.size() < 2 || later.size() < 2 || !callsHaveEndpointOwner(slice.contract())
                    || views == null) {
                result.add(new DeliverySlice(slice.id(), slice.name(), slice.acceptanceCriteria(),
                        slice.files(), slice.contract(), dependencies, slice.sharedFiles()));
                lastIdByOriginal.put(slice.id(), slice.id());
                continue;
            }
            String coreId = slice.id() + "-create-query";
            String laterId = slice.id() + "-change-actions";
            ProjectContract coreContract = scopedContract(slice.contract(), core, views.core());
            ProjectContract laterContract = scopedContract(slice.contract(), later, views.later());
            List<DeliverySlice> coreMilestones = splitPageDetail(slice, coreContract, dependencies);
            if (coreMilestones.isEmpty()) {
                result.add(new DeliverySlice(coreId, slice.name() + "：创建与查询",
                        criteria(coreContract), slice.files(), coreContract, dependencies, slice.sharedFiles()));
            } else {
                result.addAll(coreMilestones);
                coreId = coreMilestones.getLast().id();
            }
            result.add(new DeliverySlice(laterId, slice.name() + "：修改与附加动作",
                    criteria(laterContract), List.of(), laterContract, List.of(coreId), slice.sharedFiles()));
            lastIdByOriginal.put(slice.id(), laterId);
        }
        return new SliceDeliveryPlan(result, plan.sharedFiles(), plan.policyVersion());
    }

    /** 页面资源足够大且详情视图归属明确时，把发布列表与详情查询分别验收。 */
    private List<DeliverySlice> splitPageDetail(DeliverySlice slice, ProjectContract coreContract,
                                                List<String> dependencies) {
        if (coreContract.endpoints().size() < 4 || coreContract.views().size() < 3) return List.of();
        List<ApiEndpointContract> detail = coreContract.endpoints().stream()
                .filter(endpoint -> "GET".equals(endpoint.method())
                        && endpoint.path().matches(".*/\\{[^}]+}$")
                        && coreContract.views().stream().anyMatch(view -> returnsView(endpoint, view)))
                .toList();
        List<ApiEndpointContract> base = coreContract.endpoints().stream()
                .filter(endpoint -> !detail.contains(endpoint)).toList();
        if (detail.isEmpty() || base.size() < 2) return List.of();
        List<ViewContract> detailViews = coreContract.views().stream()
                .filter(view -> detail.stream().anyMatch(endpoint -> returnsView(endpoint, view))
                        && base.stream().noneMatch(endpoint -> returnsView(endpoint, view))).toList();
        List<ViewContract> baseViews = coreContract.views().stream()
                .filter(view -> !detailViews.contains(view)).toList();
        if (baseViews.isEmpty()) return List.of();
        ProjectContract baseContract = new ProjectContract(base, baseViews,
                callsFor(coreContract.frontendCalls(), base), List.of());
        ProjectContract detailContract = new ProjectContract(detail, detailViews,
                callsFor(coreContract.frontendCalls(), detail), List.of());
        String baseId = slice.id() + "-create-list";
        String detailId = slice.id() + "-detail-query";
        return List.of(
                new DeliverySlice(baseId, slice.name() + "：创建与列表",
                        criteria(baseContract), slice.files(), baseContract, dependencies, slice.sharedFiles()),
                new DeliverySlice(detailId, slice.name() + "：详情查询",
                        criteria(detailContract), List.of(), detailContract, List.of(baseId), slice.sharedFiles()));
    }

    /** 只依据契约中的模板路径关联页面，允许响应说明在路径后附加“view”等文字。 */
    private boolean returnsView(ApiEndpointContract endpoint, ViewContract view) {
        String response = endpoint.responseDto().replace('\\', '/');
        String template = view.templatePath().replace('\\', '/');
        int templates = template.indexOf("/templates/");
        String relative = templates < 0 ? template : template.substring(templates + "/templates/".length());
        return relative.endsWith(".html") && response.contains(relative);
    }

    /** 前端交互跟随其实际调用的接口。 */
    private List<FrontendCallContract> callsFor(List<FrontendCallContract> calls,
                                                List<ApiEndpointContract> endpoints) {
        return calls.stream().filter(call -> endpoints.stream()
                .anyMatch(endpoint -> matches(endpoint, call))).toList();
    }

    /** 更新、删除及资源下的动作接口在基础创建查询通过后交付。 */
    private boolean laterAction(ApiEndpointContract endpoint) {
        String path = endpoint.path().toLowerCase(java.util.Locale.ROOT).replaceAll("\\{[^}]+}", "{}");
        if (path.matches(".*(?:/edit|/delete|/vote|/votes|/results|/comments)$")) return true;
        if (List.of("PUT", "PATCH", "DELETE").contains(endpoint.method())) return true;
        return "POST".equals(endpoint.method()) && path.contains("/{}")
                && !path.endsWith("/new");
    }

    /** 页面只有明确归属或属于独立的共享处理器时才允许拆分。 */
    private ViewPartition partitionViews(ProjectContract contract, List<ApiEndpointContract> core,
                                         List<ApiEndpointContract> later) {
        List<ViewContract> coreViews = new ArrayList<>();
        List<ViewContract> laterViews = new ArrayList<>();
        for (ViewContract view : contract.views()) {
            boolean usedByCore = core.stream().anyMatch(endpoint -> returnsView(endpoint, view));
            boolean usedByLater = later.stream().anyMatch(endpoint -> returnsView(endpoint, view));
            if (!usedByCore && !usedByLater) {
                boolean sameController = view.returnedBy().isBlank() || contract.endpoints().stream()
                        .anyMatch(endpoint -> endpoint.implementedBy().equals(view.returnedBy()));
                // 同一业务 Controller 的页面用途不明时保留原任务，不能提前交付未来页面。
                if (sameController) return null;
                usedByCore = true;
            }
            if (usedByCore) coreViews.add(view);
            if (usedByLater) laterViews.add(view);
        }
        return new ViewPartition(coreViews, laterViews);
    }

    private record ViewPartition(List<ViewContract> core, List<ViewContract> later) { }

    /** 前端调用按所调用端点归组，页面按已确认的端点归属交付。 */
    private ProjectContract scopedContract(ProjectContract original, List<ApiEndpointContract> endpoints,
                                           List<ViewContract> views) {
        List<FrontendCallContract> calls = original.frontendCalls().stream()
                .filter(call -> endpoints.stream().anyMatch(endpoint -> matches(endpoint, call))).toList();
        return new ProjectContract(endpoints, views, calls, List.of());
    }

    /** 前端调用无法归属时保留原任务，防止拆分时遗漏已规划的跨层契约。 */
    private boolean callsHaveEndpointOwner(ProjectContract contract) {
        return contract.frontendCalls().stream().allMatch(call -> contract.endpoints().stream()
                .anyMatch(endpoint -> matches(endpoint, call)));
    }

    private boolean matches(ApiEndpointContract endpoint, FrontendCallContract call) {
        return endpoint.method().equals(call.method())
                && canonicalPath(endpoint.path()).equals(canonicalPath(call.path()));
    }

    /** 将路径参数的名字归一化，允许契约与前端模板使用不同变量名。 */
    private String canonicalPath(String path) {
        return (path == null ? "" : path).replaceAll("\\$?\\{[^}]+}", "{}");
    }

    /** 验收描述只列出本任务的公开契约，避免首任务承诺尚未实现的未来动作。 */
    private List<String> criteria(ProjectContract contract) {
        List<String> criteria = new ArrayList<>();
        contract.endpoints().forEach(endpoint -> criteria.add(endpoint.method() + " " + endpoint.path()
                + " is implemented and tested."));
        contract.views().forEach(view -> criteria.add("View " + view.name() + " resolves template "
                + view.templatePath() + "."));
        return criteria;
    }
}
