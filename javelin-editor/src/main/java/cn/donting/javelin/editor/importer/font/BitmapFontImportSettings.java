package cn.donting.javelin.editor.importer.font;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.editor.importer.ImportSettings;

/**
 * {@code .chars} 字符集导入设置。
 *
 * <p>当前无额外配置项（烘焙字体固定为打包思源黑体 Bold、格子 16x16、阈值 1bpp）。
 * {@link #getType()} = "BitmapFont" 与 AssetMeta 的 assetType 值一致。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public class BitmapFontImportSettings extends ImportSettings {

    public BitmapFontImportSettings() {
    }

    @Override
    public String getType() {
        return "BitmapFont";
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
