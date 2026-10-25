package com.javelin.editor.importer.image;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.editor.importer.ImportSettings;

/**
 * 图片导入设置。
 *
 * <p>当前版本无额外配置项（统一 RGBA8 直读；mip 生成/压缩/sRGB 分支后置，
 * Engine_Texture_Design §4）。{@link #getType()} = "Texture" 与
 * MetaManager.detectAssetType 的 .png/.jpg 检测值一致。</p>
 */
@Reflective
public class ImageImportSettings extends ImportSettings {

    public ImageImportSettings() {
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
