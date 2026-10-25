package com.javelin.editor.importer.texture;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.editor.importer.ImportSettings;

/**
 * 纹理导入设置。
 *
 * <p>当前版本无额外配置项（采样/派生设置存 .texture 源本体，非导入设置）。
 * {@link #getType()} = "Texture" 与 MetaManager.detectAssetType 的
 * .texture 检测值一致。</p>
 */
@Reflective
public class TextureImportSettings extends ImportSettings {

    public TextureImportSettings() {
    }

    @Override
    public String getType() {
        return "Texture";
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
