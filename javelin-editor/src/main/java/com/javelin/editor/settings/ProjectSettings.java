package com.javelin.editor.settings;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.core.serialize.Serializable;

/**
 * 项目偏好设置。
 *
 * <p>存储编辑器级别的偏好配置，如布局、主题等。</p>
 *
 * @author javelin
 * @since 1.0
 */
@Reflective
public class ProjectSettings implements Serializable {

    /**
     * 默认场景路径（编辑器启动时自动打开）。
     * 基于Assets 相对目录
     */
    private String defaultOpenScene;

    public ProjectSettings() {
    }

    public String getDefaultOpenScene() {
        return defaultOpenScene;
    }

    public void setDefaultOpenScene(String defaultOpenScene) {
        this.defaultOpenScene = defaultOpenScene;
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("defaultOpenScene", defaultOpenScene);
    }

    @Override
    public void read(InputCapsule input) {
        this.defaultOpenScene = input.readString("defaultOpenScene");
    }
}
