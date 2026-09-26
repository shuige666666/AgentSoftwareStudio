package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在真实工作区应用多文件补丁；先检查全部匹配，再逐文件写入并报告实际落盘结果。
 */
@Service
public class WorkspacePatchService {

    public record PatchResult(boolean ok, List<String> applied, String failedPath,
                              String error, String message) { }

    private record Change(String path, Path target, String before, String after) { }

    /**
     * 支持 Add、Update、Delete 和多个上下文块，写入失败时保留已应用文件的准确列表。
     */
    public PatchResult apply(String patch, WorkspaceAgentSession session) {
        List<Change> changes;
        try {
            changes = prepare(patch, session);
        } catch (PatchException e) {
            return new PatchResult(false, List.of(), e.path, e.code, e.getMessage());
        }
        List<String> applied = new ArrayList<>();
        for (Change change : changes) {
            try {
                // 预检后若文件被外部进程改动，停止后续写入并明确报告局部成功。
                String current = Files.exists(change.target())
                        ? Files.readString(change.target(), StandardCharsets.UTF_8) : null;
                if (!java.util.Objects.equals(current, change.before())) {
                    return new PatchResult(false, List.copyOf(applied), change.path(),
                            "STALE_FILE", "File changed after patch validation; inspect current disk state");
                }
                if (change.after() == null) {
                    Files.delete(change.target());
                } else {
                    writeAtomic(change.target(), change.after());
                }
                session.recordWrite(change.path());
                applied.add(change.path());
            } catch (IOException e) {
                return new PatchResult(false, List.copyOf(applied), change.path(),
                        "WRITE_FAILED", e.getMessage());
            }
        }
        return new PatchResult(true, List.copyOf(applied), "", "", "Patch applied");
    }

    private List<Change> prepare(String patch, WorkspaceAgentSession session) {
        if (patch == null || patch.isBlank()) throw failure("", "EMPTY_PATCH", "Patch is required");
        if (!patch.stripLeading().startsWith("*** Begin Patch")) patch = convertUnifiedDiff(patch);
        else patch = patch.stripLeading();
        String[] lines = patch.replace("\r\n", "\n").split("\n", -1);
        if (!"*** Begin Patch".equals(lines[0])) {
            throw failure("", "PATCH_FORMAT", "Patch must start with *** Begin Patch");
        }
        Map<String, Change> changes = new LinkedHashMap<>();
        int index = 1;
        while (index < lines.length && !"*** End Patch".equals(lines[index])) {
            String header = lines[index++];
            String operation;
            if (header.startsWith("*** Add File: ")) operation = "add";
            else if (header.startsWith("*** Update File: ")) operation = "update";
            else if (header.startsWith("*** Delete File: ")) operation = "delete";
            else throw failure("", "PATCH_FORMAT", "Expected file operation: " + header);
            String raw = header.substring(header.indexOf(": ") + 2);
            String relative = safePath(raw, session);
            if (changes.containsKey(relative)) {
                throw failure(relative, "DUPLICATE_FILE", "Combine changes for a file into one patch block");
            }
            Path target = session.projectRoot().resolve(relative).normalize();
            try {
                String before = Files.exists(target) ? Files.readString(target, StandardCharsets.UTF_8) : null;
                if ("add".equals(operation) && before != null) {
                    throw failure(relative, "FILE_EXISTS", "New file already exists");
                }
                if (!"add".equals(operation) && before == null) {
                    throw failure(relative, "FILE_NOT_FOUND", "File to update or delete does not exist");
                }
                String after = null;
                if ("add".equals(operation)) {
                    List<String> added = new ArrayList<>();
                    while (index < lines.length && !lines[index].startsWith("*** ")) {
                        String line = lines[index++];
                        if (!line.startsWith("+")) throw failure(relative, "PATCH_FORMAT", "Added file lines must start with +");
                        added.add(line.substring(1));
                    }
                    if (added.isEmpty()) throw failure(relative, "EMPTY_FILE", "New file needs content");
                    after = String.join("\n", added) + "\n";
                } else if ("update".equals(operation)) {
                    List<String> updated = new ArrayList<>(List.of(before.replace("\r\n", "\n").split("\n", -1)));
                    int cursor = 0;
                    int hunks = 0;
                    while (index < lines.length && !lines[index].startsWith("*** ")) {
                        if (!lines[index].startsWith("@@")) {
                            throw failure(relative, "PATCH_FORMAT", "Update block needs an @@ context hunk");
                        }
                        index++;
                        List<String> oldLines = new ArrayList<>();
                        List<String> newLines = new ArrayList<>();
                        while (index < lines.length && !lines[index].startsWith("*** ")
                                && !lines[index].startsWith("@@")) {
                            String line = lines[index++];
                            if (line.isEmpty()) throw failure(relative, "PATCH_FORMAT", "Hunk lines need a prefix");
                            char prefix = line.charAt(0);
                            String content = line.substring(1);
                            if (prefix == ' ' || prefix == '-') oldLines.add(content);
                            if (prefix == ' ' || prefix == '+') newLines.add(content);
                            if (prefix != ' ' && prefix != '-' && prefix != '+') {
                                throw failure(relative, "PATCH_FORMAT", "Unknown hunk prefix: " + prefix);
                            }
                        }
                        if (oldLines.isEmpty()) throw failure(relative, "PATCH_CONTEXT", "Update hunk needs matching context");
                        int location = uniqueMatch(updated, oldLines, cursor, relative);
                        updated.subList(location, location + oldLines.size()).clear();
                        updated.addAll(location, newLines);
                        cursor = location + newLines.size();
                        hunks++;
                    }
                    if (hunks == 0) throw failure(relative, "PATCH_FORMAT", "Update block contains no hunks");
                    after = String.join("\n", updated);
                }
                // 常见补丁会在最后一个 hunk 后附加文件结束标记，它不是新文件操作。
                if (index < lines.length && "*** End of File".equals(lines[index])) index++;
                changes.put(relative, new Change(relative, target, before, after));
            } catch (IOException e) {
                throw failure(relative, "READ_FAILED", e.getMessage());
            }
        }
        if (index >= lines.length || !"*** End Patch".equals(lines[index]) || changes.isEmpty()) {
            throw failure("", "PATCH_FORMAT", "Patch must contain operations and end with *** End Patch");
        }
        for (int tail = index + 1; tail < lines.length; tail++) {
            if (!lines[tail].isBlank()) throw failure("", "PATCH_FORMAT", "Unexpected content after patch end");
        }
        return List.copyOf(changes.values());
    }

    /** 模型若返回标准 unified diff，转换为同一套已预检的多文件补丁语义。 */
    private String convertUnifiedDiff(String patch) {
        String[] lines = patch.replace("\r\n", "\n").split("\n", -1);
        StringBuilder converted = new StringBuilder("*** Begin Patch\n");
        int index = 0;
        int files = 0;
        while (index < lines.length) {
            if (!isUnifiedHeader(lines, index)) {
                if (lines[index].isBlank() || lines[index].startsWith("diff --git ")
                        || lines[index].startsWith("index ") || lines[index].startsWith("new file mode ")
                        || lines[index].startsWith("deleted file mode ")) {
                    index++;
                    continue;
                }
                throw failure("", "PATCH_FORMAT", "Expected unified diff file header");
            }
            String oldPath = lines[index++].substring(4).strip();
            String newPath = lines[index++].substring(4).strip();
            // 一些模型用普通 a/b 路径表示新增文件，但 hunk 的旧范围仍是 -0,0。
            boolean add = "/dev/null".equals(oldPath)
                    || (index < lines.length && lines[index].startsWith("@@ -0,0 "));
            boolean delete = "/dev/null".equals(newPath);
            String path = stripDiffPrefix(add ? newPath : oldPath);
            converted.append("*** ").append(add ? "Add" : delete ? "Delete" : "Update")
                    .append(" File: ").append(path).append('\n');
            files++;
            boolean hunkSeen = false;
            while (index < lines.length && !isUnifiedHeader(lines, index)) {
                String line = lines[index++];
                if (line.startsWith("@@ ")) {
                    hunkSeen = true;
                    if (!add && !delete) converted.append("@@\n");
                    continue;
                }
                if (line.startsWith("\\ No newline at end of file")
                        || line.startsWith("diff --git ") || line.startsWith("index ")) continue;
                if (add && hunkSeen) {
                    // 新增文件的旧范围为零，模型偶尔省略后续行的 +；整行仍是新内容。
                    converted.append(line.startsWith("+") ? line : "+" + line).append('\n');
                    continue;
                }
                if (line.isBlank()) continue;
                if (!hunkSeen || (line.charAt(0) != '+' && line.charAt(0) != '-'
                        && line.charAt(0) != ' ')) {
                    throw failure(path, "PATCH_FORMAT", "Invalid unified diff hunk line");
                }
                if (!delete) converted.append(line).append('\n');
            }
            if (!hunkSeen) throw failure(path, "PATCH_FORMAT", "Unified diff file has no hunk");
        }
        if (files == 0) throw failure("", "PATCH_FORMAT", "No unified diff files found");
        return converted.append("*** End Patch\n").toString();
    }

    private boolean isUnifiedHeader(String[] lines, int index) {
        return index + 1 < lines.length && lines[index].startsWith("--- ")
                && lines[index + 1].startsWith("+++ ");
    }

    private String stripDiffPrefix(String path) {
        return path.startsWith("a/") || path.startsWith("b/") ? path.substring(2) : path;
    }

    private int uniqueMatch(List<String> lines, List<String> oldLines, int from, String path) {
        int location = -1;
        for (int start = from; start + oldLines.size() <= lines.size(); start++) {
            if (lines.subList(start, start + oldLines.size()).equals(oldLines)) {
                if (location >= 0) throw failure(path, "PATCH_AMBIGUOUS", "Context matches more than once");
                location = start;
            }
        }
        if (location < 0) throw failure(path, "PATCH_CONTEXT_NOT_FOUND", "Context does not match current file");
        return location;
    }

    private String safePath(String raw, WorkspaceAgentSession session) {
        if (raw == null || raw.isBlank()) throw failure("", "INVALID_PATH", "Project-relative path is required");
        Path path = Path.of(raw.replace('\\', '/')).normalize();
        String relative = path.toString().replace('\\', '/');
        if (path.isAbsolute() || path.startsWith("..") || relative.equals(".")
                || relative.startsWith(".git/") || relative.startsWith(".software-studio/")
                || relative.startsWith("target/") || relative.startsWith("studio-run-logs/")) {
            throw failure(relative, "PATH_OUT_OF_SCOPE", "Patch path is outside editable source and project config");
        }
        boolean allowed = relative.startsWith("src/") || relative.equals("pom.xml")
                || relative.equals("README.md") || relative.equals(".gitignore");
        if (!allowed) throw failure(relative, "PATH_OUT_OF_SCOPE", "Patch path is outside editable source and project config");
        Path root = session.projectRoot();
        Path target = root.resolve(path).normalize();
        if (!target.startsWith(root)) throw failure(relative, "PATH_OUT_OF_SCOPE", "Patch path escapes workspace");
        for (Path candidate = target; candidate.startsWith(root) && !candidate.equals(root);
             candidate = candidate.getParent()) {
            if (Files.isSymbolicLink(candidate)) throw failure(relative, "SYMLINK_PATH", "Patch cannot traverse symlinks");
        }
        return relative;
    }

    private void writeAtomic(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".studio-patch-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private PatchException failure(String path, String code, String message) {
        return new PatchException(path, code, message);
    }

    private static final class PatchException extends RuntimeException {
        private final String path;
        private final String code;

        private PatchException(String path, String code, String message) {
            super(message);
            this.path = path;
            this.code = code;
        }
    }
}
