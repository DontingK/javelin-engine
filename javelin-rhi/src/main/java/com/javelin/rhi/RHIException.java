package com.javelin.rhi;

/**
 * RHI 根异常。
 */
public class RHIException extends RuntimeException {

    public RHIException(String message) {
        super(message);
    }

    public RHIException(String message, Throwable cause) {
        super(message, cause);
    }
}
