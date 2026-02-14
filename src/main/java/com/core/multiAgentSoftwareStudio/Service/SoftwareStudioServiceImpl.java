package com.core.multiAgentSoftwareStudio.Service;

import com.core.multiAgentSoftwareStudio.Agent.ArchitectAgent;
import com.core.multiAgentSoftwareStudio.Agent.DeveloperAgent;
import com.core.multiAgentSoftwareStudio.Agent.ProductManagerAgent;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.PrdDocument;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.SourceCode;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class SoftwareStudioServiceImpl implements SoftwareStudioService {

    private final ProductManagerAgent pmAgent;
    private final ArchitectAgent architectAgent;
    private final DeveloperAgent developerAgent;
    private final DockerSandboxService sandboxService;
    private final WorkspaceService workspaceService;

    // Spring 构造器注入 (需要在 Config 里通过 AiServices 创建这些 Bean)
    public SoftwareStudioServiceImpl(ProductManagerAgent pm, ArchitectAgent arch, DeveloperAgent dev, DockerSandboxService sandboxService, WorkspaceService workspaceService) {
        this.pmAgent = pm;
        this.architectAgent = arch;
        this.developerAgent = dev;
        this.sandboxService = sandboxService;
        this.workspaceService = workspaceService;
    }

    public List<SourceCode> generateProject(String userRequest){
        System.out.println("🚀 1. 产品经理正在分析需求: " + userRequest);
        PrdDocument prd = pmAgent.analyzeRequirement(userRequest);
        System.out.println("✅ PRD 生成完毕: " + prd.projectName());
        System.out.println(prd);
        System.out.println("🏗️ 2. 架构师正在设计系统结构...");
        ProjectStructure structure = architectAgent.designArchitecture(prd);
        System.out.println("✅ 架构设计完毕，共 " + structure.files().size() + " 个文件。");
        System.out.println(structure);
        // 代码文件列表
        List<SourceCode> codes = new ArrayList<>();


        // 并行或者串行生成代码
        for (FileBlueprint fileBlueprint : structure.files()) {
            System.out.println("👨‍💻 3. 工程师正在编写: " + fileBlueprint.fileName());

            SourceCode rawResult = developerAgent.writeCode(
                    prd,
                    fileBlueprint.fileName(),
                    fileBlueprint.functionalityDescription(),
                    fileBlueprint.keyMethods().toString() // List 转 String
            );

            // 2. 强制使用蓝图中的文件名，防止 AI 幻觉
            // SourceCode 是 record，不可变，所以我们要 new 一个新的
            SourceCode finalCode = new SourceCode(
                    fileBlueprint.fileName(), // 强制使用正确的文件名
                    rawResult.language(),
                    rawResult.code()          // 只取 AI 生成的代码内容
            );
            codes.add(finalCode);

            System.out.println("✅ 文件完成: " + finalCode.filename());
        }

        // 4. 持久化到本地 ---
        Path projectPath = workspaceService.saveProjectToDisk(prd.projectName(), codes);

        // 5. 沙箱运行
        System.out.println("🐳 正在启动 Docker 沙箱运行代码...");
        String executionResult = sandboxService.runCodeInSandbox(projectPath);

        System.out.println("💡 运行结果:");
        System.out.println("--------------------------------------------------");
        System.out.println(executionResult);
        System.out.println("--------------------------------------------------");
        return codes;
    }
}
