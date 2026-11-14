package com.example.finance.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A small thread-safe least-recently-used cache. The web server keeps uploaded datasets in one,
 * so memory stays bounded however many visitors upload files.
 */
public final class LruCache<K, V> {
    private final Map<K, V> cache;

    public LruCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > capacity;
            }
        });
    }

    public void put(K key, V value) {
        cache.put(key, value);
    }

    public Optional<V> get(K key) {
        return Optional.ofNullable(cache.get(key));
    }

    public int size() {
        return cache.size();
    }
}
