package com.core.multiAgentSoftwareStudio.Model.Repair;

// 代码修复类
public record CodeFix(
        String filename,        // 需要修改的文件名 (如 Main.java)
        String explanation,     // 简短解释为什么报错以及怎么修的
        String newCode          // 修复后的完整新代码 (注意：是完整的代码，不要打补丁，方便直接覆盖)
) {}
