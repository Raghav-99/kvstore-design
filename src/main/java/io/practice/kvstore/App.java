package io.practice.kvstore;

import java.io.File;
import java.util.Arrays;
import java.util.Scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.practice.kvstore.v1.core.Engine;
import io.practice.kvstore.v1.core.StoreHandle;

/**
 * Hello world!
 */
public class App {
    private static Logger logger = LoggerFactory.getLogger(App.class); 
    public static void main(String[] args) {
        File f = new File("./db/store.log");
        try {
            Engine engine = new Engine();
            StoreHandle handle = engine.open(f);
            String ch;
            Scanner sc = new Scanner(System.in);
            do {
                System.out.println("Enter command (get/put/del) or n to exit: ");
                ch = sc.nextLine().trim();
                switch(ch) {
                    case "get": {
                        System.out.println("Enter key to get value: ");
                        String key = sc.nextLine().trim();
                        byte[] val = engine.get(key);
                        logger.info("get: key={}, value={}", key, new String(val == null ? new byte[0] : val));
                        break;
                    }
                    case "put": {
                        System.out.println("Enter key to put: ");
                        String key = sc.nextLine().trim();
                        System.out.println("Enter value to put: ");
                        String value = sc.nextLine().trim();
                        boolean success = engine.put(handle, key, value.getBytes());
                        logger.info("put: {} for key {}", success, key);
                        break;
                    }
                    case "del": {
                        System.out.println("Enter key to delete: ");
                        String key = sc.nextLine().trim();
                        boolean success = engine.delete(key);
                        logger.info("del: {} for key {}", success, key);
                        break;
                    }
                }
            }while(ch.compareToIgnoreCase("n") != 0);
            sc.close();
        } catch (Exception e) {
            logger.error("Error occurred: {}", Arrays.toString(e.getStackTrace()));
            e.printStackTrace();
        }
    }
}
