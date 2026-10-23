package io.practice.kvstore.v1.core;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.InputStreamReader;
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
}
