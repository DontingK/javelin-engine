package com.javelin.editor.importer.shader;

import com.javelin.editor.i18n.I18nKey;

/**
 * .shader 解析异常（RHI 21 节语法）：携带源文件名与行号，导入器捕获后
 * 上报到 ImportTask 错误信息（导入失败 → 引擎替换内置默认材质，RHI 21.6-4）。
 * 消息收 {@link I18nKey}（需要国际化的异常面显式标出）。
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class ShaderParseException extends RuntimeException {

    private final String sourceName;
    private final int line;

    public ShaderParseException(I18nKey message, String sourceName, int line) {
        super(message.text() + "（" + sourceName + ":" + line + "）");
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
