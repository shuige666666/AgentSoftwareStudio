package com.core.multiAgentSoftwareStudio.Pojo.teamCommunication;

import java.util.Collections;
import java.util.List;

// 3. 单个文件蓝图 (架构师发给开发者的指令)
    public record FileBlueprint(
        String fileName,                // e.g., "GameController.java"
        String filePath,                // e.g., "src/main/java/com/..."
        String functionalityDescription,// 这个文件具体的职责描述
        List<String> keyMethods         // 建议包含的关键方法名
    ) {// 在对象创建时自动执行拦截 keyMethods （这里有时候会漏写然后报错）
    public FileBlueprint {
        if (keyMethods == null) {
            keyMethods = Collections.emptyList(); // 如果 JSON 里没这个字段，强制转换为空列表
        }
        if (functionalityDescription == null) {
            functionalityDescription = "请根据文件名和上下文实现具体逻辑。"; // 其他字段也可以这么做兜底
        }
    }}