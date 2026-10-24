package io.practice.kvstore.v1.core;

import java.io.File;
import java.io.IOException;

public interface Commands {
    StoreHandle open(File file) throws IOException;
    byte[] get(String key) throws IOException;
    boolean put(StoreHandle handle, String key, byte[] value) throws IOException;
    boolean delete(String key);
    void close(StoreHandle handle) throws Exception;
}
