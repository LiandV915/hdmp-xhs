package com.hmdp.utils;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
public class InMemoryChatMemory {
    /*当前系统定位为轻量级单机场景，为了降低复杂度和开发成本，先采用基于内存的上下文管理方案。
    使用滑动窗口机制控制上下文长度，保证性能稳定。
    后续如果扩展到多实例或需要持久化，可以很方便迁移到 Redis 实现分布式共享。
     */

    private static final int MAX_SIZE = 10;
    private final ConcurrentHashMap<Long, Deque<String>> store = new ConcurrentHashMap<>();

    public Deque<String> getContext(Long userId) {
        return store.getOrDefault(userId, new ConcurrentLinkedDeque<>());
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
