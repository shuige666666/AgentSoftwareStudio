package com.example.polls;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 已有创建和查询；投票及身份去重尚未实现。 */
@Service
public class PollStore {
    public record Poll(long id, String question, List<String> options) { }
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, Poll> polls = new ConcurrentHashMap<>();

    /** 保存独立投票，复制选项避免外部修改。 */
    public Poll create(String question, List<String> options) {
        Poll poll = new Poll(ids.incrementAndGet(), question, List.copyOf(options));
        polls.put(poll.id(), poll);
        return poll;
    }
    public Optional<Poll> find(long id) { return Optional.ofNullable(polls.get(id)); }
    public Map<String, Integer> counts(long id) {
        Map<String, Integer> result = new LinkedHashMap<>();
        polls.get(id).options().forEach(option -> result.put(option, 0));
        return result;
    }
}
