package cn.donting.javelin.editor.exception;


import cn.donting.javelin.editor.i18n.I18nKey;

/**
 * 业务提示异常。
 *
 * @create: 2026/6/25
 * @Version 1.0
 **/
public class TipException extends LangException {

    public TipException(I18nKey message) {
        super(message);
    }

    public TipException(I18nKey message, Throwable cause) {
        super(message, cause);
    }
}

