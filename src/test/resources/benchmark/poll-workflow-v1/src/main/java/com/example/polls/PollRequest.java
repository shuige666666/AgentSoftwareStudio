package com.example.polls;
import java.util.List;
public record PollRequest(String question, List<String> options) { }
