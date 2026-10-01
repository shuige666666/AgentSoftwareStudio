# Note form v1

Java 17, Spring Boot 3.2.4 with Thymeleaf, Maven, default port8080, in-memory store.
Existing: GET /notes list HTML, GET /notes/new form with title/body fields, GET /notes/{id} detail or404.

Pending: POST /notes form-urlencoded title/body ->302 or303 Location=/notes; persist original values.
Null/blank title or body ->400 with the form fields visible, no state mutation.
Render all user text escaped, no executable HTML from title/body.
Detail must include a POST /notes/{id}/delete form.
POST /notes/{id}/delete ->302 or303 Location=/notes with actual deletion; missing ->404.
Preserve valid baseline tests, add meaningful tests and update this README. No edit, auth or extra features.
