---
name: agent-software-studio-runbook
description: Run and diagnose the agent-software-studio Java project on this Windows workspace. Use when working in agent-software-studio, running Maven tests, starting the Spring Boot app, diagnosing JDK version problems, Maven dependency/network failures, generated-project repair loops, or Docker/interactive test hangs.
---

# Agent Software Studio Runbook

## Scope

Use this skill in `C:\My Space\Other Projects\agent 软件开发小组\agent-software-studio` when the task involves running, testing, or diagnosing this project. The repo is a Java 21 Spring Boot application with Maven, LangGraph4j, LangChain4j, Docker sandbox integration, and generated projects under `ai_generated_projects`.

## Quick Start

The Windows command-line `java`/`mvn` may default to JDK 8, while the project requires Java 21 or newer. Before running Maven, check the active runtime:

```powershell
java -version
mvn -version
```

If Maven reports Java 8, run Maven with IntelliJ's bundled JBR for this command only:

```powershell
$env:JAVA_HOME="C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\jbr"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -Dtest=SomeFocusedTest test
```

Do not treat Java text block compile errors in agent prompt classes as source corruption until the Maven runtime has been checked. With JDK 8, files such as `AbstractJsonAgent.java` and `ArchitectAgent.java` can fail with misleading errors like `未结束的字符串文字` or `需要';'`.

## Maven Network Failures

The sandbox may block Maven from resolving dependencies. A typical failure is:

```text
Non-resolvable parent POM
Could not transfer artifact org.springframework.boot:spring-boot-starter-parent:pom:3.5.10
Permission denied: connect
```

When this happens, rerun the same Maven command with escalated permissions so Maven can access the dependency repository. Keep the command focused, for example:

```powershell
$env:JAVA_HOME="C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\jbr"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -Dtest=CodeContextBuilderServiceTest test
```

## Test Strategy

Prefer narrow tests over full `mvn test` while diagnosing. This repo contains tests that may depend on Docker or interactive input, especially `DockerSandboxServiceTest`, so full test runs can hang or fail for environmental reasons unrelated to the code under inspection.

Good default:

```powershell
mvn -Dtest=ClassNameTest test
```

Run full test suites only after checking whether Docker or interactive tests are in scope. If the task is about a service method, write a small JUnit test that instantiates the service directly instead of booting the Spring context.

## Generated Project Repair Loop

Generated projects live under `ai_generated_projects`. When the workflow fails after sandbox verification, identify the earliest real failure first:

1. Inspect the Maven compiler/runtime log for the generated project.
2. Open the referenced generated file with UTF-8.
3. Separate generated-code bugs from workflow-repair bugs.

For example, this pattern means the generated Java source is invalid:

```text
/app/src/main/java/com/calculator/controller/CalculatorController.java:[1,8] <identifier> expected
```

If that file starts with `package ...`, the generated code is invalid because `...` is not a Java package name.

This later error is a repair-flow bug, not the original compiler root cause:

```text
Cannot invoke "String.split(String)" because "code" is null
```

In this codebase, check `ProjectRepairService`, `CodeContextBuilderService`, `BatchGenerationService`, and `SourceCodePathService`. Repair context construction happens before `DebuggerAgent.analyzeAndFix`, so a null-code crash after `9. Invoking debugger agent for final repair.` may mean the debugger agent was never actually called.

## Known Robustness Pattern

`SourceCode.code()` can be null if the model returns JSON with `code: null`. Keep both entry-point and read-side defenses:

- Generation and fix entry points should normalize `null` code to `""` before storing in `data.codes`.
- Context builders should use a safe accessor before appending or summarizing code.
- Summary extraction should return `// [No summary available]` for null or blank code.

This prevents Maven compilation failures from being masked by workflow-level `NullPointerException`s.

## Git Hygiene

This workspace may contain unrelated local changes, such as `缓存命中记录.md`. Check `git status --short` before edits and do not stage or revert unrelated files.
