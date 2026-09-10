package com.javelin.editor.exception;

/**
 * 构建异常。
 *
 * <p>Gradle 编译、GraalVM Native 编译、打包等构建操作失败时抛出。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class BuildException extends RuntimeException {

    public BuildException(String message) {
        super(message);
    }

    public BuildException(String message, Throwable cause) {
        super(message, cause);
    }
}
