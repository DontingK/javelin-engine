package com.javelin.editor.util;


/**
 * @author: javelin
 * @create: 2026/4/4
 * @Version 1.0
 **/
public class ThreadUtil {

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
    public static void run(Runnable runnable) {
        new Thread(runnable).start();
    }
    public static void run(String name,Runnable runnable) {
        Thread thread = new Thread(runnable);
        thread.setName(name);
        thread.start();
    }
    public static void waitRun(Runnable runnable,long millis) {
        new Thread(() -> {
            sleep(millis);
            runnable.run();
        }).start();
    }
}
