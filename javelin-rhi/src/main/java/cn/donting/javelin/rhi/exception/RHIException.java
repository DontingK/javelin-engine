package cn.donting.javelin.rhi.exception;

/**
 * RHI 根异常。
 * 所有 RHI 层抛出的运行时异常继承此类。
 */
public class RHIException extends RuntimeException {

    public RHIException(String message) {
        super(message);
    }

    public RHIException(String message, Throwable cause) {
        super(message, cause);
    }
}
