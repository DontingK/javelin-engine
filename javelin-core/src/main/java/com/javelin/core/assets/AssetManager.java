package com.javelin.core.assets;


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

}
