package com.javelin.editor.exception;

/**
 * 资源操作异常。
 *
 * <p>资源导入、导出、识别、预览等操作失败时抛出。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class AssetException extends RuntimeException {

    public AssetException(String message) {
        super(message);
    }

    public AssetException(String message, Throwable cause) {
        super(message, cause);
    }
}
