package com.javelin.editor.exception;

/**
 * 场景操作异常。
 *
 * <p>场景加载、保存、序列化、反序列化等操作失败时抛出。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class SceneException extends RuntimeException {

    public SceneException(String message) {
        super(message);
    }

    public SceneException(String message, Throwable cause) {
        super(message, cause);
    }
}
