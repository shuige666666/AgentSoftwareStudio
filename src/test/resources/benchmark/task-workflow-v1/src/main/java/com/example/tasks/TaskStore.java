package com.example.tasks;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 已有内存存储支持创建与查询；后续能力继续使用同一份状态。 */
@Service
public class TaskStore {
    private final ConcurrentHashMap<Long, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    /** 分配真实 ID，保存未完成任务。 */
    public Task create(String title) {
        Task task = new Task(ids.incrementAndGet(), title, false);
        tasks.put(task.id(), task);
        return task;
    }
    /** 查询当前状态，不存在时由 HTTP 层返回 404。 */
    public Optional<Task> find(long id) { return Optional.ofNullable(tasks.get(id)); }
    /** 用 ID 顺序返回列表，不依赖哈希表迭代顺序。 */
    public List<Task> list() { return tasks.values().stream().sorted(java.util.Comparator.comparingLong(Task::id)).toList(); }
}
