package com.example.tasks;

/** 公开任务状态；完成动作应保留 ID 与标题。 */
public record Task(long id, String title, boolean completed) { }
