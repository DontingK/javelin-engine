package com.javelin.editor.importer.shader;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.editor.importer.ImportSettings;

/**
 * .shader 导入设置。
 *
 * <p>当前版本无额外配置项（0 变体策略，RHI 20.5；开关属性走 uniform 分支）。
 * {@link #getType()} = "Shader" 与 MetaManager.detectAssetType 的 .shader 检测值一致。</p>
 */
@Reflective
public class ShaderImportSettings extends ImportSettings {

    public ShaderImportSettings() {
    }

    @Override
    public String getType() {
        return "Shader";
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
