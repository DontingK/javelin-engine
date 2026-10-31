package cn.donting.javelin.editor.settings;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

/**
 * 编辑器全局设置。
 *
 * <p>存储与具体项目无关的编辑器偏好，例如新建项目时使用的默认目录。</p>
 *
 * @author javelin
 * @since 1.0
 */
@Reflective
public class EditorSettings implements Serializable {

    /** 新建项目时默认打开的父目录路径。 */
    private String newProjectPath;

    /** 界面语言（lang 文件名，如 "en_US"；null = 未设置，首启按系统语言解析）。 */
    private String language;

    /**
     * 获取新建项目时默认打开的父目录路径。
     *
     * @return 父目录路径，可能为 {@code null}
     */
    public String getNewProjectPath() {
        return newProjectPath;
    }

    /**
     * 设置新建项目时默认打开的父目录路径。
     *
     * @param newProjectPath 父目录路径
     */
    public void setNewProjectPath(String newProjectPath) {
        this.newProjectPath = newProjectPath;
    }

    /**
     * 获取界面语言。
     *
     * @return lang 文件名，可能为 {@code null}（未设置，首启按系统语言解析）
     */
    public String getLanguage() {
        return language;
    }

    /**
     * 设置界面语言。
     *
     * @param language lang 文件名（I18n 语言标识，语言注册表键）
     */
    public void setLanguage(String language) {
        this.language = language;
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("newProjectPath", newProjectPath);
        output.write("language", language);
    }

    @Override
    public void read(InputCapsule input) {
        newProjectPath = input.readString("newProjectPath");
        language = input.readString("language");
    }
}
