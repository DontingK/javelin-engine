package com.javelin.editor.exception;


/**
 * 业务提示异常。
 *
 * @create: 2026/6/25
 * @Version 1.0
 **/
public class TipException extends RuntimeException {
    public TipException(String message) {
        super(message);
    }

    public TipException(String message, Throwable cause) {
        super(message, cause);
    }
}

