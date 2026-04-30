package com.core.multiAgentSoftwareStudio.Pojo.teamCommunication;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

// 3. 单个文件蓝图 (架构师发给开发者的指令)
public record FileBlueprint(
        String fileName,
        String filePath,
        String layer,
        String batchName,
        String functionalityDescription,
        List<String> keyMethods,
        List<String> dependsOn
) implements Serializable {
    private static final long serialVersionUID = 1L;

    public FileBlueprint {
        if (keyMethods == null) {
            keyMethods = Collections.emptyList();
        }
        if (dependsOn == null) {
            dependsOn = Collections.emptyList();
        }
        if (functionalityDescription == null || functionalityDescription.isBlank()) {
            functionalityDescription = "请根据文件名和上下文实现具体逻辑。";
        }
        if (layer == null || layer.isBlank()) {
            layer = inferLayer(filePath, fileName);
        } else {
            layer = layer.trim().toLowerCase();
        }
    }

    public String targetPath() {
        if (filePath != null && !filePath.isBlank()) {
            return normalizePath(filePath);
        }
        return normalizePath(fileName);
    }

    public String effectiveLayer() {
        if (layer == null || layer.isBlank()) {
            return inferLayer(filePath, fileName);
        }
        return layer.trim().toLowerCase();
    }

    private static String inferLayer(String filePath, String fileName) {
        String path = normalizePath(filePath != null && !filePath.isBlank() ? filePath : fileName).toLowerCase();

        if (path.contains("/controller/")) {
            return "controller";
        }
        if (path.contains("/service/") || path.contains("/repository/")) {
            return "service";
        }
        if (path.contains("/dto/") || path.contains("/entity/") || path.contains("/model/")
                || path.contains("/config/") || path.contains("/util/")) {
            return "base";
        }
        if (path.endsWith(".html") || path.endsWith(".css") || path.endsWith(".js")) {
            return "frontend";
        }
        if (path.contains("src/test/java/") || path.endsWith("test.java")) {
            return "test";
        }
        return "base";
    }

    private static String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "Unknown.java";
        }
        return rawPath.trim().replace("\\", "/");
    }
}
