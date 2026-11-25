package com.connect_screen.mirror;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

final class BoundedLogBuffer {
    private final int capacity;
    private final Deque<String> lines;

    BoundedLogBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.lines = new ArrayDeque<>(capacity);
    }

    synchronized void add(String line) {
        if (lines.size() == capacity) {
            lines.removeFirst();
        }
        lines.addLast(line);
    }

    synchronized List<String> snapshot() {
        return new ArrayList<>(lines);
    }

    synchronized int size() {
        return lines.size();
    }
}
