package com.javelin.editor.importer.shader;

/**
 * .shader 解析异常（RHI 21 节语法）：携带源文件名与行号，导入器捕获后
 * 上报到 ImportTask 错误信息（导入失败 → 引擎替换内置默认材质，RHI 21.6-4）。
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class ShaderParseException extends RuntimeException {

    private final String sourceName;
    private final int line;

    public ShaderParseException(String message, String sourceName, int line) {
        super(message + "（" + sourceName + ":" + line + "）");
        this.sourceName = sourceName;
        this.line = line;
    }

    public String getSourceName() {
        return sourceName;
    }

    public int getLine() {
        return line;
    }
}
