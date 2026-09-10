package com.javelin.editor.meta;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.core.serialize.Serializable;
import com.javelin.editor.importer.ImportSettings;

import java.util.Objects;

/**
 * 资源元数据
 *
 * <p>存储资源的导入设置和缓存信息</p>
 *
 * @author: javelin
 * @create: 2026/4/7
 * @Version 1.0
 */
@Reflective
public class AssetMeta implements Serializable {

    /**
     * 全局唯一标识符
     */
    private String guid;

    /**
     * 资源类型
     */
    private String assetType;

    /**
     * 导入设置（具体类型由资源决定）
     */
    private ImportSettings importSettings;

    /**
     * 源文件最后修改时间（毫秒时间戳）
     */
    private long lastModifiedTime;

    public AssetMeta() {
    }

    public AssetMeta(String guid, String assetType) {
        this.guid = guid;
        this.assetType = assetType;
    }

    public String getGuid() {
        return guid;
    }

    public void setGuid(String guid) {
        this.guid = guid;
    }

    public String getAssetType() {
        return assetType;
    }

    public void setAssetType(String assetType) {
        this.assetType = assetType;
    }

    public ImportSettings getImportSettings() {
        return importSettings;
    }

    public void setImportSettings(ImportSettings importSettings) {
        this.importSettings = importSettings;
    }

    public long getLastModifiedTime() {
        return lastModifiedTime;
    }

    public void setLastModifiedTime(long lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("guid", guid);
        output.write("assetType", assetType);
        output.write("importSettings", importSettings);
        output.write("lastModifiedTime", lastModifiedTime);
    }

    @Override
    public void read(InputCapsule input) {
        this.guid = input.readString("guid");
        this.assetType = input.readString("assetType");
        this.importSettings = input.readSerializable("importSettings", ImportSettings.class);
        this.lastModifiedTime = input.readLong("lastModifiedTime");
    }



    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AssetMeta assetMeta = (AssetMeta) o;
        return Objects.equals(guid, assetMeta.guid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(guid);
    }


}
