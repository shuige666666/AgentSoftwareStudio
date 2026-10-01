# Poll workflow v1

Java 17, Spring Boot 3.2.4, Maven, default port 8080, in-memory store.
Existing: POST /api/polls JSON question/options -> 201 {id,question,options}; invalid question/options ->400.
GET /api/polls/{id} ->200 poll or404; GET /api/polls/{id}/results ->200 {counts:{option:integer}} or404.

Pending: POST /api/polls/{id}/votes JSON {voterId,option} ->200 on valid first vote.
Missing poll ->404; null/blank voterId or invalid option ->400; previously used voterId in SAME poll ->409.
Successful votes persist in results. Rejected requests must not change counts or consume identity.
The same voterId may vote in a different poll. Keep valid baseline tests, add meaningful tests, update this README.
