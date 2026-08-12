package com.core.multiAgentSoftwareStudio.Model.Generation;

import java.io.Serializable;
import java.util.List;

/**
 * 保存按依赖顺序排列的垂直切片和项目级共享文件。
 */
public record SliceDeliveryPlan(
        List<DeliverySlice> slices,
        List<String> sharedFiles,
        String policyVersion) implements Serializable {

    private static final long serialVersionUID = 1L;
    public static final String CURRENT_POLICY_VERSION = "vertical-slice-v6";

    public SliceDeliveryPlan {
        slices = slices == null ? List.of() : List.copyOf(slices);
        sharedFiles = sharedFiles == null ? List.of() : List.copyOf(sharedFiles);
        policyVersion = policyVersion == null || policyVersion.isBlank()
                ? CURRENT_POLICY_VERSION
                : policyVersion;
    }

    public static SliceDeliveryPlan empty() {
        return new SliceDeliveryPlan(List.of(), List.of(), CURRENT_POLICY_VERSION);
    }
}
