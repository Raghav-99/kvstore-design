package io.practice.kvstore.v1.core;

import java.util.HashMap;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Getter;

public class Keydir {
    private final Map<String, KeydirEntry> hashIndex = new HashMap<>();
    
    public void put(String key, StoreHandle handle, long tmstamp, long value) {
        hashIndex.put(key, new KeydirEntry(handle, tmstamp, value));
    }

    public boolean contains(String key) {
        return hashIndex.containsKey(key);
    }
    public KeydirEntry get(String key) {
        return hashIndex.getOrDefault(key, null);
    }

    public boolean delete(String key) {
        return hashIndex.remove(key) != null;
    }

    @Getter  
    static class KeydirEntry {
        private StoreHandle handle;
        private long tmstamp;
        private long valOffset; 
        KeydirEntry(StoreHandle handle, long tmstamp, long valOffset) {
            this.handle = handle;
            this.tmstamp = tmstamp;
            this.valOffset = valOffset;
        }
    }
}
