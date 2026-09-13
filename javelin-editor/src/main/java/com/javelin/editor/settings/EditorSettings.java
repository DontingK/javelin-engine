package com.javelin.editor.settings;

import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.core.serialize.Serializable;

/**
 * 编辑器全局设置。
 *
 * <p>存储与具体项目无关的编辑器偏好，例如新建项目时使用的默认目录。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class EditorSettings implements Serializable {

    /** 新建项目时默认打开的父目录路径。 */
    private String newProjectPath;

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

    @Override
    public void write(OutputCapsule output) {
        output.write("newProjectPath", newProjectPath);
    }

    @Override
    public void read(InputCapsule input) {
        newProjectPath = input.readString("newProjectPath");
    }
}
