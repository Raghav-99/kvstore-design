package io.practice.kvstore.v1.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class EngineTest {
    private Engine engine;
    private Charset charset = Charset.forName("US-ASCII");
    private File dbFile;
    private StoreHandle handle;

    @BeforeEach 
    void init(@TempDir File tmpDir) throws IOException {
        dbFile = new File(tmpDir, "/db/store.log");
        engine = new Engine();
        handle = engine.open(dbFile);
    }

    @AfterEach
    void closeHandle() throws Exception {
        engine.close(handle);
    }

    @Nested 
    public class BufferTest {
        @Test 
        void KeyValueLessThan1KBTest() throws IOException {
            String key = "test", value = "hello world";
            assertTrue(engine.put(handle, key, value.getBytes(charset)));
            byte[] res = engine.get(key);
            assertEquals(value, new String(res));
        }

        @Test
        void KeyMoreThan1KBTest() throws IOException {
            String key = new String(new byte[1025]), value = "hello world";
            assertTrue(engine.put(handle, key, value.getBytes(charset)));
            byte[] res = engine.get(key);
            assertEquals(value, new String(res));
        }

        @Test
        void ValueMoreThan1KBTest() throws IOException {
            String key = "test";
            byte[] value = new byte[1025];
            assertTrue(engine.put(handle, key, value));
            byte[] res = engine.get(key);
            assertEquals(new String(value), new String(res));
        }

        @Test
        void KeyValueMoreThan1KBTest() throws IOException {
            String key = new String(new byte[1025]);
            byte[] value = new byte[1025];
            assertTrue(engine.put(handle, key, value));
            byte[] res = engine.get(key);
            assertEquals(new String(value), new String(res));
        }
    }
}
