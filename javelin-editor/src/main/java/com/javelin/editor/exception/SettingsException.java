package com.javelin.editor.exception;

/**
 * 设置操作异常。
 *
 * <p>设置文件读写、解析、验证等操作失败时抛出。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class SettingsException extends RuntimeException {

    public SettingsException(String message) {
        super(message);
    }

    public SettingsException(String message, Throwable cause) {
        super(message, cause);
    }
}
