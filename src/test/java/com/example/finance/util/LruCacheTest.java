package com.example.finance.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LruCacheTest {
    @Test
    void evictsTheLeastRecentlyUsedEntry() {
        LruCache<String, Integer> cache = new LruCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        assertEquals(Integer.valueOf(1), cache.get("a").orElseThrow()); // touching "a" makes "b" the eldest
        cache.put("c", 3);
        assertTrue(cache.get("a").isPresent());
        assertFalse(cache.get("b").isPresent());
        assertEquals(2, cache.size());
    }

    @Test
    void rejectsAZeroCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new LruCache<String, String>(0));
    }
}
