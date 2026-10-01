package com.example.notes;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Controller
public class NoteController {
    private final NoteStore store;
    public NoteController(NoteStore store) { this.store = store; }
    @GetMapping("/notes")
    public String list(Model model) { model.addAttribute("notes", store.list()); return "notes/list"; }
    @GetMapping("/notes/new")
    public String form() { return "notes/form"; }
    @GetMapping("/notes/{id}")
    public String detail(@PathVariable long id, Model model) {
        model.addAttribute("note", store.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        return "notes/detail";
    }
}
