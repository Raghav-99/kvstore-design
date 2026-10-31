package io.practice.kvstore.server.v1.core;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.practice.kvstore.server.v1.core.Keydir.KeydirEntry;

// uses a singular dedicated buffer for now
public class Engine implements Commands {
    private final Logger logger = LoggerFactory.getLogger(this.getClass()); 
    private final Keydir keydir = new Keydir();
    final String HEADER = "%kvstore.v1";
    final ByteBuffer buffer = ByteBuffer.wrap(new byte[1024]);
    
    @Override
    public StoreHandle open(File file) throws IOException {
        boolean isNewFile = !file.exists();
        if (isNewFile) {
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists() && parentDir.getName().equals("db")) {
                parentDir.mkdirs();
            }
            file.createNewFile();
            logger.info("New store.log was created");
        }
        FileChannel wChannel = FileChannel.open(file.toPath(), StandardOpenOption.APPEND);
        FileChannel rChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
        if(isNewFile) {
            if(wChannel.write(ByteBuffer.wrap(HEADER.getBytes())) != HEADER.length()) {
                throw new IOException("Failed to write header");
            }
            return new StoreHandle(rChannel, wChannel);
        }
        byte[] headerBytes = new byte[HEADER.length()];
        rChannel.read(ByteBuffer.wrap(headerBytes));
        String fileHeader = new String(headerBytes);
        if (!fileHeader.equals(HEADER)) {
            throw new IOException("Invalid file format");
        }
        logger.info("Handle returned");
        return new StoreHandle(rChannel, wChannel);
    }

    // todo: enforce chunk by chunk reading like put
    @Override
    public byte[] get(String key) throws IOException {
        logger.info("get started for key: {}", key);
        ByteBuffer rbf = buffer;
        KeydirEntry record = keydir.get(key);
        if(record != null) {
            FileChannel channel = record.getHandle().getRChannel().position(record.getValOffset());
            
            rbf.clear(); // only applicable for v1
            if(isCrcValid(channel, rbf)) {
                // todo: consolidate this with crc calc
                // shift to val offset now
                channel.position(channel.position() + 12 + key.length()); // 8 bytes for tmstamp, 4 bytes for keySz and rest actual key size
                return readChunks(rbf, channel);
            } // todo: do something if crc doesn't match
        }
        return null;
    }
    
    private boolean isCrcValid(FileChannel channel, ByteBuffer rbf) throws IOException {
        // channel = channel.position(channel.position()/*  + 8*/); // crc offset
        rbf.limit(8);
        channel.read(rbf);
        long actualCrc = rbf.getLong(0), expectedCrc = 1000L;
        // todo: add crc calc logic
        rbf.clear();
        return actualCrc == expectedCrc;
    }

    // todo: add streaming later
    // todo: add buffer underflow handling
    private byte[] readChunks(ByteBuffer rbf, FileChannel channel) throws IOException {
        rbf.limit(4);
        channel.read(rbf);
        int valSz = rbf.getInt(0);
        logger.info("get: value size is {}", valSz);
        byte[] val = new byte[valSz];
        
        int oVal = 0, rc = 0;
        do {
            rbf.clear().limit(Math.min(valSz - oVal, rbf.capacity()));
            int c = channel.read(rbf);
            if(c == -1) {
                logger.info("disk read: eof encountered");
                return null;
            }
            logger.info("disk read: read {} bytes from store.log", c);
            rc += c;
            rbf.flip().get(val, oVal, c);
            oVal += c;
        }while(rc < valSz);
        rbf.clear();
        logger.info("disk read: returned value of size {} bytes", val.length);
        return val;
    }

    // todo: support utf-16
    @Override
    public boolean put(StoreHandle handle, String key, byte[] value) throws IOException {
        logger.info("put started for key: {}, value size: {}", key, value.length);
        final int tmstmpSz = 8, crcSz = 8, keySz=4+key.length(), valSz = 4+value.length;
        FileChannel channel = handle.getWChannel();
        long pos = channel.position();
        
        // first 8 bytes is the size of whole chunk, 8 and 8 bytes for crc and tmstamp, 
        // 4 bytes for key length plus the bytes of key itself, 4 bytes for value length plus the bytes of value itself
        long chunkSize = crcSz + tmstmpSz + keySz + valSz;
        logger.debug("put: chunk size(={}) = crcSz(={}) + tmstmpSz(={}) + keySz(=4+{}) + valSz(=4+{})", chunkSize, crcSz, tmstmpSz, key.length()*2, value.length);
        long timestamp = Instant.now().toEpochMilli();
        // todo: add real crc value
        byte[] metadata = ByteBuffer.allocate(20).putLong(1000L).putLong(timestamp).putInt(key.length()).array(), valueMetadata = ByteBuffer.allocate(4).putInt(value.length).array();
        int wc = processChunk(metadata, buffer, channel, CHUNK_TYPE.HEADER) 
                + processChunk(key.getBytes(StandardCharsets.UTF_8), buffer, channel, CHUNK_TYPE.KEY) 
                + processChunk(valueMetadata, buffer, channel, CHUNK_TYPE.VALUE_LEN) 
                + processChunk(value, buffer, channel, CHUNK_TYPE.VALUE);
        if(wc != chunkSize) {
            logger.error("put failed: chunk size and final disk write count didn't match");
            logger.debug("wc={}, chunk_size={}", wc, chunkSize);
            return false;
        }
        keydir.put(key, handle, timestamp, pos);
        return true;
    }

    private int writeChunks(ByteBuffer wbf, FileChannel channel) throws IOException {
        int write = channel.write(wbf.flip()); // todo: add forced flushing?
        wbf.clear();
        return write;
    }

    @Override
    public boolean delete(String key) {
        logger.info("delete started for key: {}", key);
        return keydir.delete(key);
    }

    @Override
    public void close(StoreHandle handle) throws Exception {
        if(handle != null) {
            handle.close();
            logger.info("closed open channels");
        }
    }

    private int fillBuffer(byte[] data, int i) {
        ByteBuffer bf = buffer;
        while(i < data.length && bf.hasRemaining()) {
            int count = Math.min(data.length - i, bf.remaining());
            bf.put(data, i, count);
            i += count;
        }
        return i;
    }

    private int processChunk(byte[] data, ByteBuffer wbf, FileChannel channel, CHUNK_TYPE chType) throws IOException {
        logger.info("Processing chunk: {}", chType.name());
        int wc = 0;
        for (int i = 0, filled; i < data.length; i+=filled) {
            filled = fillBuffer(data, i);
            logger.debug("chType = {}, filled={}, buff_remaining={}", chType.name(), filled, wbf.remaining());
            if(!wbf.hasRemaining()) {
                int w = writeChunks(wbf, channel);
                logger.info("{} bytes written to disk for chunk {}. left to write: {}", w, chType.name(), data.length - w);
                wc += w;
            }
        }
        if(wbf.remaining() < wbf.capacity()) {
            int w = writeChunks(wbf, channel);
            logger.info("{} bytes written to disk for chunk {}. left to write: {}", w, chType.name(), data.length - w);
            wc += w;
        }
        return wc;
    }

    enum CHUNK_TYPE {
        HEADER,
        KEY,
        VALUE_LEN,
        VALUE
    }
}
