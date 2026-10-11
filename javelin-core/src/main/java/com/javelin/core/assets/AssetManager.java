package com.javelin.core.assets;


import java.util.List;

public interface AssetManager {

    /**
     * 按 GUID 加载资源
     *
     * @param guid       资源 GUID
     * @param assetClass 资源类型
     * @param <T>        资源泛型
     * @return 加载的资源，找不到时返回 null
     */
    <T extends Asset> T loadGuid(String guid, Class<T> assetClass);
    <T extends Asset> T loadGuidInstance(String guid, Class<T> assetClass);

    /**
     * 按相对路径加载资源
     *
     * @param path       相对于 Library 的相对路径
     * @param assetClass 资源类型
     * @param <T>        资源泛型
     * @return 加载的资源，找不到时返回 null
     */
    <T extends Asset> T loadAsset(String path, Class<T> assetClass);
    <T extends Asset> T loadAssetInstance(String path, Class<T> assetClass);

    /**
     * 列出索引中指定后缀的资产相对路径（忽略大小写匹配，如 {@code ".scene"}）。
     *
     * <p>实现侧应先重载索引再过滤（拾取运行期新增导入——索引文件由导入管线
     * 在每次导入后重写，内存表不自动跟进）。返回排序稳定（字典序）。</p>
     *
     * @param suffix 资产路径后缀（含点号）
     * @return 匹配的相对路径列表（无序时空）
     */
    List<String> listAssetPaths(String suffix);

}
