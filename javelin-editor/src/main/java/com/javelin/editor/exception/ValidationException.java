package com.javelin.editor.exception;

/**
 * 参数校验异常。
 *
 * <p>用户输入、表单数据、API 参数等校验失败时抛出。
 * 通常配合 {@link TipException} 一起使用，将校验错误友好地展示给用户。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
