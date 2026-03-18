package com.core.multiAgentSoftwareStudio.Service;

import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.SourceCode;

import java.util.List;
import java.util.function.Consumer;

public interface SoftwareStudioService {
        List<SourceCode> generateProject(String userRequest);

        void generateProjectStream(String userRequest, Consumer<String> eventListener);
}
