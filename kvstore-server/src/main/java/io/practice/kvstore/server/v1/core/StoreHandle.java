package io.practice.kvstore.server.v1.core;

import java.nio.channels.FileChannel;

import lombok.Getter;

@Getter 
public class StoreHandle {
    private FileChannel rChannel;
    private FileChannel wChannel;
    // private FileDescriptor fd;
    public StoreHandle(FileChannel rChannel, FileChannel wChannel) {
        this.rChannel = rChannel;
        this.wChannel = wChannel;
        // this.fd = fileDescriptor;
    }

    public void close() throws Exception {
        if(rChannel != null) {
            rChannel.close();
        }
        if(wChannel != null) {
            wChannel.close();
        }
    }
}
