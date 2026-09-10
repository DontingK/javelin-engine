package com.javelin.tool.shaderc;

/**
 * 着色器编译异常。
 *
 * <p>当 GLSL 源码编译为 SPIR-V 失败时抛出，携带编译器的错误日志。</p>
 *
 * @author Phillip Webb
 * @since 1.0.0
 */
public class ShaderCompileException extends RuntimeException {

    /**
     * 构造编译异常。
     *
     * @param message 异常描述信息
     */
    public ShaderCompileException(String message) {
        super(message);
    }

    /**
     * 构造编译异常。
     *
     * @param message 异常描述信息
     * @param cause   原始异常
     */
    public ShaderCompileException(String message, Throwable cause) {
        super(message, cause);
    }
}
