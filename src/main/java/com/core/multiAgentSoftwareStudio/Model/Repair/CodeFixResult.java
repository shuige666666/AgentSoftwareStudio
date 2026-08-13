package com.core.multiAgentSoftwareStudio.Model.Repair;

import java.util.List;

// 用这个对象来包装 List
public record CodeFixResult(
        List<CodeFix> fixes
) {}