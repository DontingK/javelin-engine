package com.javelin.editor.exception;

/**
 * 项目操作异常。
 *
 * <p>项目打开、创建、导入、删除等操作失败时抛出。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class ProjectException extends RuntimeException {

    public ProjectException(String message) {
        super(message);
    }

    public ProjectException(String message, Throwable cause) {
        super(message, cause);
    }
}
