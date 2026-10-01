package com.example.tasks;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TasksApplication {
    /** 启动已有任务 API。 */
    public static void main(String[] args) { SpringApplication.run(TasksApplication.class, args); }
}
