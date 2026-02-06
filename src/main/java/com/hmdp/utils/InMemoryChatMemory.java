package com.hmdp.utils;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryChatMemory {

    private static final int MAX_SIZE = 4;
    private final ConcurrentHashMap<Long, Deque<String>> store = new ConcurrentHashMap<>();

    public Deque<String> getContext(Long userId) {
        return store.getOrDefault(userId, new ArrayDeque<>());
    }

    public void append(Long userId, String role, String content) {
        store.computeIfAbsent(userId, k -> new ArrayDeque<>())
                .addLast(role + "：" + content);

        Deque<String> deque = store.get(userId);
        while (deque.size() > MAX_SIZE) {
            deque.pollFirst();
        }
    }
}
