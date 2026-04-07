package com.core.multiAgentSoftwareStudio.Agent;

import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.PrdDocument;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.SourceCode;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.ProjectStructure;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 角色 C: 工程师 (Software Engineer)
 */
public interface DeveloperAgent {

        @SystemMessage("""
                        You are a Senior Java Developer. Your task is to implement ONE specific file based on the architecture design.
                        You will receive a Global PRD, the Project Structure (context), and a SPECIFIC FILE TASK.

                        Guidelines:
                        1. Write production-ready, compiling code.
                        2. Include necessary imports and package declarations.
                        3. Do NOT use placeholders like '// TODO: implement logic', write the actual logic.
                        4. Ensure the code aligns with the PRD and the overall Project Structure.
                        5. CRITICAL: Pay attention to the interaction between files. For example:
                           - Controller endpoints MUST match what the Frontend expects.
                           - Service methods MUST match what the Controller calls.
                           - Field names in DTOs/Models MUST be consistent across the project.

                        CRITICAL RULES:
                        1. You must ONLY implement the code for the 'Current Target File'.
                        2. Do NOT implement the whole project. Focus strictly on the single class requested.
                        3. The 'filename' in your JSON output MUST match the 'Current Target File' name exactly.
                        4. Do NOT use markdown code blocks, just return a valid JSON object.

                        JSON FORMAT RULES:
                        1. The output MUST be a valid JSON object matching the `SourceCode` record.
                        2. The `code` field MUST be a single string. All newlines in the code MUST be escaped as `\\n`.
                        3. Escape any double quotes (`"`) as `\\"` inside the code string.
                        """)
        @UserMessage("""
                        === GLOBAL CONTEXT (Reference Only) ===
                        {{prd}}

                        === PROJECT STRUCTURE (Reference Only) ===
                        {{structure}}

                        === ALREADY IMPLEMENTED FILES (Reference Only) ===
                        {{existingCode}}

                        =======================================
                        === CURRENT TARGET FILE (IMPLEMENT THIS) ===
                        Filename: {{fileName}}
                        Description: {{description}}
                        Required Methods: {{methods}}
                        =======================================

                        Write the Java code for '{{fileName}}' now.
                        """)
        SourceCode writeCode(
                        @V("prd") PrdDocument prd,
                        @V("structure") ProjectStructure structure,
                        @V("existingCode") String existingCode,
                        @V("fileName") String fileName,
                        @V("description") String description,
                        @V("methods") String methods);
}