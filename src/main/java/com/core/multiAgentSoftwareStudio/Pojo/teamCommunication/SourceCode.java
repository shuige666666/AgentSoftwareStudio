package com.core.multiAgentSoftwareStudio.Pojo.teamCommunication;

// 4. 源代码 (工程师的产出)
    public record SourceCode(
            String filename,
            String language,                // "java"
            String code                     // 完整的代码内容
    ) {}