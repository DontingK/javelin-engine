package com.javelin.core;


import java.io.File;
import java.io.IOException;

/**
 * @create: 2026/6/30
 * @Version 1.0
 **/
public class Env {

    static {
        try {
            System.setProperty("core.runtime.dir", new File("./").getCanonicalPath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    public static File getRunDir(){
        String workDir = System.getProperty("core.runtime.dir");
        return new File(workDir);
    }

    public static boolean isRelease() {
        return false;
    }
}
