package com.connect_screen.mirror;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.Arrays;

public class BoundedLogBufferTest {
    @Test
    public void addKeepsOnlyNewestLines() {
        BoundedLogBuffer buffer = new BoundedLogBuffer(3);

        buffer.add("one");
        buffer.add("two");
        buffer.add("three");
        buffer.add("four");

        assertEquals(3, buffer.size());
        assertEquals(Arrays.asList("two", "three", "four"), buffer.snapshot());
    }

    @Test
    public void snapshotIsIndependentFromFutureWrites() {
        BoundedLogBuffer buffer = new BoundedLogBuffer(2);
        buffer.add("one");
        java.util.List<String> snapshot = buffer.snapshot();

        buffer.add("two");
        buffer.add("three");

        assertEquals(Arrays.asList("one"), snapshot);
        assertEquals(Arrays.asList("two", "three"), buffer.snapshot());
    }

    @Test
    public void capacityMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedLogBuffer(0));
    }
}
