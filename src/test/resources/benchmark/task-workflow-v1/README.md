# Task workflow shared starting point

Java 17 / Spring Boot 3.2.4. Run `mvn test` and `mvn spring-boot:run` (port 8080).

Implemented: create, list, query and their baseline tests. Pending: complete and delete.
This is a new partial fixture; no tests were removed from an existing generated project.

## Fixed public contract: task-workflow-behavior-v1

- `POST /api/tasks`, JSON `{ "title": "Acceptance task" }`: 201, numeric id, same title, completed=false. Blank, null or more than 100 characters: 400 without writing.
- `GET /api/tasks`: 200 JSON array of stored tasks; `GET /api/tasks/{id}`: 200 task, absent id: 404.
- `POST /api/tasks/{id}/complete`: 200 same id/title, completed=true persisted; repeat: 200 unchanged; absent id: 404.
- `DELETE /api/tasks/{id}`: 204 and remove from storage; subsequent query: 404; absent id: 404.

Preserve existing behavior and tests, add meaningful tests for new capabilities, and update run documentation.
