package com.core.multiAgentSoftwareStudio.Service;

import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.SourceCode;

import java.util.List;

public interface SoftwareStudioService {
    List<SourceCode> generateProject(String userRequest);
}
