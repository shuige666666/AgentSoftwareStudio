package com.core.multiAgentSoftwareStudio.Pojo.teamCommunication;

import java.util.List;

// 用这个对象来包装 List
public record CodeFixResult(
        List<CodeFix> fixes
) {}