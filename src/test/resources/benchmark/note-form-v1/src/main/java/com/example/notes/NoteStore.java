package com.example.notes;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class NoteStore {
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, Note> notes = new ConcurrentHashMap<>();
    /** 已有内存存储供页面查询；HTTP 提交和删除尚未接入。 */
    public Note create(String title, String body) {
        Note note = new Note(ids.incrementAndGet(), title, body);
        notes.put(note.id(), note);
        return note;
    }
    public Optional<Note> find(long id) { return Optional.ofNullable(notes.get(id)); }
    public List<Note> list() { return notes.values().stream().sorted(Comparator.comparingLong(Note::id)).toList(); }
}
