package com.hmdp.utils;

import java.util.*;
public class InMemoryChatMemory{
    private List<String> messages;

    public InMemoryChatMemory() {
        this.messages = new ArrayList<>();
    }

    public void addMessage(String message) {
        messages.add(message);
    }

    public List<String> getMessages() {
        return new ArrayList<>(messages);
    }

    public void clearMessages() {
        messages.clear();
    }
}