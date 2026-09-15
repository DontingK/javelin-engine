package com.javelin.rhi;

/**
 * RHI 运行时异常，封装底层图形 API 的错误信息。
 */
public class RHIException extends RuntimeException {

    /** 使用指定消息构造异常。 */
    public RHIException(String message) {
        super(message);
    }

    /** 使用指定消息和原因构造异常。 */
    public RHIException(String message, Throwable cause) {
        super(message, cause);
    }
}
