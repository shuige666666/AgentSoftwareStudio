package com.example.polls;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/polls")
public class PollController {
    private final PollStore store;
    public PollController(PollStore store) { this.store = store; }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody PollRequest request) {
        if (request.question() == null || request.question().isBlank() || request.options() == null
                || request.options().size() < 2 || request.options().stream().anyMatch(s -> s == null || s.isBlank())
                || request.options().stream().distinct().count() != request.options().size())
            return ResponseEntity.badRequest().build();
        return ResponseEntity.status(201).body(store.create(request.question(), request.options()));
    }
    @GetMapping("/{id}")
    public ResponseEntity<PollStore.Poll> query(@PathVariable long id) { return ResponseEntity.of(store.find(id)); }
    @GetMapping("/{id}/results")
    public ResponseEntity<?> results(@PathVariable long id) {
        if (store.find(id).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("counts", store.counts(id)));
    }
}
