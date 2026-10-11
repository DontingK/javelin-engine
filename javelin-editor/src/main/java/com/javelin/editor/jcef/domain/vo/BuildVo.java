package com.javelin.editor.jcef.domain.vo;

/**
 * 构建结果视图。
 *
 * @author javelin
 * @version 1.0
 * @create 2026/10/11
 **/
public class BuildVo {
    /** 构建日志（按行拼接，Gradle 输出 + 产物组装信息） */
    public String log;

    public BuildVo(String log) {
        this.log = log;
    }
}
