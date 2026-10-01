package com.example.tasks;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

/** 已实现创建、列表、查询；完成与删除尚未实现。 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskStore store;
    public TaskController(TaskStore store) { this.store = store; }

    /** 标题校验失败不写存储，合法请求保留原值。 */
    @PostMapping
    public ResponseEntity<Task> create(@RequestBody TaskRequest request) {
        if (request.title() == null || request.title().isBlank() || request.title().length() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title must be nonblank and at most 100 characters");
        return ResponseEntity.status(HttpStatus.CREATED).body(store.create(request.title()));
    }
    /** 返回所有已保存任务。 */
    @GetMapping
    public List<Task> list() { return store.list(); }
    /** 返回真实任务，不存在时响应 404。 */
    @GetMapping("/{id}")
    public ResponseEntity<Task> get(@PathVariable long id) { return ResponseEntity.of(store.find(id)); }
}
