package com.javelin.core.render.shader;

/**
 * Shader DSL 解析与预处理异常。
 *
 * <p>消息格式为 {@code [行:列] 描述}，同时保留数值形式的行列号便于工具消费。</p>
 */
public class ShaderParseException extends RuntimeException {

    /** 出错行号（1 起始），语义校验类错误可能为 0 */
    public final int line;
    /** 出错列号（1 起始），语义校验类错误可能为 0 */
    public final int column;

    /**
     * 构造解析异常。
     *
     * @param message 错误描述
     * @param line    行号（1 起始）
     * @param column  列号（1 起始）
     */
    public ShaderParseException(String message, int line, int column) {
        super(String.format("[%d:%d] %s", line, column, message));
        this.line = line;
        this.column = column;
    }
}
