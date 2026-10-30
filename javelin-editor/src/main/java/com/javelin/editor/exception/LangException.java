package com.javelin.editor.exception;

import com.javelin.editor.i18n.I18nKey;

/**
 * 国际化异常基类：消息收 {@link I18nKey}（构造期经 key.text() 解析为当前语言文案）——
 * 需要国际化的异常面以收键显式标出，诊断面文案仍走裸 String。
 */
public class LangException extends Exception {

    public LangException(I18nKey message) {
        super(message.text());
    }

    public LangException(I18nKey message, Throwable cause) {
        super(message.text(), cause);
    }
}
