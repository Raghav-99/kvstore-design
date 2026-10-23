package io.practice.kvstore.v1.core;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.practice.kvstore.v1.core.Keydir.KeydirEntry;

public class Engine implements Commands {
    private final Logger logger = LoggerFactory.getLogger(this.getClass()); 
    private final Keydir keydir = new Keydir();
    final String HEADER = "%kvstore.v1";
    // final byte[] buffer = new byte[1024]; 
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

    @Override
    public byte[] get(String key) throws IOException {
        logger.info("get started for key: {}", key);
        KeydirEntry record = keydir.get(key);
        if(record != null) {
            FileChannel channel = record.getHandle().getRChannel().position(record.getValOffset());
            ByteBuffer rbf = buffer.clear();
            // rbf.clear(); // only applicable for v1
            if(isCrcValid(channel)) {
                // todo: consolidate this with crc calc
                // shift to val offset now
                channel.position(channel.position() + 12 + key.length()); // 8 bytes for tmstamp, 4 bytes for keySz and rest actual key size
                return readChunks(rbf, channel);
            } // todo: do something if crc doesn't match
        }
        return null;
    }
    
    private boolean isCrcValid(FileChannel channel) throws IOException {
        ByteBuffer rbf = buffer;
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
    // todo: handle buffer capacity shouldn't exceed 1kB
    private byte[] readChunks(ByteBuffer rbf, FileChannel channel) throws IOException {
        rbf.limit(4);
        channel.read(rbf);
        int valSz = rbf.getInt(0);
        logger.info("get: value size is {}", valSz);
        byte[] val = new byte[valSz];
        int oVal = 0, rc = 0;
        rbf.clear().limit(valSz);
        do {
            int c = channel.read(rbf);
            if(c == -1) {
                logger.info("get: eof encountered");
                return null;
            }
            logger.info("get: read {} bytes from store.log", c);
            rc += c;
            rbf.flip().get(val, oVal, c);
            oVal += c;
        }while(rc < valSz);
        rbf.clear();
        logger.info("get: returned value of size {} bytes", val.length);
        return val;
    }

    @Override
    // todo: make sure is buffer is being fully utilized
    public boolean put(StoreHandle handle, String key, byte[] value) throws IOException {
        logger.info("put started for key: {}, value size: {}", key, value.length);
        final int tmstmpSz = 8, crcSz = 8;
        final long keySz=4L+key.length(), valSz = 4L+value.length;
        FileChannel channel = handle.getWChannel();
        long pos = channel.position();
        
        // first 8 bytes is the size of whole chunk, 8 and 8 bytes for crc and tmstamp, 
        // 4 bytes for key length plus the bytes of key itself, 4 bytes for value length plus the bytes of value itself
        // todo: remove redundant chunksize?
        long chunkSize = crcSz + tmstmpSz + keySz + valSz;
        logger.debug("write: chunk size(={}) = crcSz(={}) + tmstmpSz(={}) + keySz(=4+{}) + valSz(=4+{})", chunkSize, crcSz, tmstmpSz, key.length(), value.length);
        long timestamp = Instant.now().toEpochMilli();
        // always in big-endian
        ByteBuffer wbf = buffer.clear();
        // wbf.putLong(chunkSize);
        // todo: add real crc value
        wbf.putLong(1000L);
        wbf.putLong(timestamp);
        wbf.putInt(key.length());
        logger.debug("write: buffer remanining size is {} bytes to write the rest of {} bytes", wbf.remaining(), chunkSize-20);
        int wc = writeChunks(wbf, channel, key.getBytes());
        logger.info("disk write: key {} of size {} was written with {} bytes. Buffer size now is: {}", key, key.length(), wc, wbf.remaining());
        if(wc > 0 && wbf.hasRemaining()) // this cond must always eval to true else something is seriously wrong with buffer
        {
            wbf.putInt(value.length);
            // channel.write(wbf);
            wc = writeChunks(wbf, channel, value);
            logger.info("disk write: key {} whose value is size {} was written with {} bytes.", key, value.length, wc);
            if(wc > 0) {
                channel.force(false);
                keydir.put(key, handle, timestamp, pos);
                return true;
            }
        }
        return false;
    }

    // todo: make write and keydir update atomic
    private int writeChunks(ByteBuffer wbf, FileChannel channel, byte[] src) throws IOException {
        int wc = 0, l = src.length, offset = 0;
        /*
            thought process:
            2 cases can happen; 1 is when the buffer has enough space to accommodate the whole src, 
            in which case we can just write it to the buffer and then write the buffer to the channel.
            2nd case is when the buffer doesn't have enough space to accommodate the whole src, 
            in which case we can write as much as we can to the buffer and then write the 
            buffer to the channel, and then repeat this process until all bytes from src are written to the channel.    

            eg for 1st case: src.length = 100, buffer.remaining() = 200, buffer filled in one go
            eg for 2nd case: src.length = 100, buffer.remaining() = 50, buffer filled in two go's
        */
        while(wc < l) {
            // carry out write if buffer is full or all bytes from src array is exhausted
            if(!wbf.hasRemaining() || offset >= l) {
                int n = channel.write(wbf.flip());
                wc += n;
                logger.info("write: {} bytes were written to store.log", n);
                wbf.clear();
            }
            else {
                int n = Math.min(wbf.remaining(), l - offset);
                wbf.put(src, offset, n);
                logger.info("write: {} bytes were written to buffer", n);
                offset += n;
            }
        }
        logger.info("a total of {} bytes were written into store.log", wc);
        // for (int i=0; i<l; i+=8) {
        //     logger.debug("writeChunk: i={}, src.length={}", i, l);
        //     if(wbf.hasRemaining()) {
        //         logger.info("before write: buffer remanining size: {}, buffer pos: {}", wbf.remaining(), wbf.position());
        //         wbf.put(src, wbf.position(), Math.min(8, src.length-wbf.position()));
        //         logger.info("after write: buffer remanining size: {}, buffer pos: {}", wbf.remaining(), wbf.position());
        //     }
        //     else {
        //         wbf.flip();
        //         wc += channel.write(wbf); // todo: log bytes written
        //         logger.info("write count={} at pos={}", wc, wbf.position());
        //         wbf.clear();
        //     }
        // }
        // if(wbf.hasRemaining()) {
        //     wbf.flip();
        //     wc += channel.write(wbf); // todo: log bytes written
        //     logger.info("write count={} at pos={}", wc, wbf.position());
        // }
        // wbf.clear();
        return wc;
    }

    @Override
    public boolean delete(String key) {
        logger.info("delete started for key: {}", key);
        return keydir.delete(key);
    }

}
