package com.javelin.editor.importer.scene;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.editor.importer.ImportSettings;

/**
 * 场景导入设置。
 *
 * <p>控制 .scene 文件的导入行为，当前版本无额外配置项。</p>
 */
@Reflective
public class SceneImportSettings extends ImportSettings {

    public SceneImportSettings() {
    }

    @Override
    public String getType() {
        return "Scene";
    }

    @Override
    public boolean validate() {
        return true;
    }

    @Override
    public void write(OutputCapsule output) {
        // 无额外字段
    }

    @Override
    public void read(InputCapsule input) {
        // 无额外字段
    }
}
