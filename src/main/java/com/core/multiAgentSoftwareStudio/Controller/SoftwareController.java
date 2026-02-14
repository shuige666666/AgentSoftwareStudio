package com.core.multiAgentSoftwareStudio.Controller;

import com.core.multiAgentSoftwareStudio.Service.SoftwareStudioService;
import com.core.multiAgentSoftwareStudio.Pojo.Result.Result;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.SourceCode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/SoftwareStudio")
public class SoftwareController {

    private final SoftwareStudioService softwareStudioService;

    @PostMapping("/chat")
    public Result<List<SourceCode>> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        List<SourceCode> result =softwareStudioService.generateProject(message);
        return Result.success(result);
    }
}