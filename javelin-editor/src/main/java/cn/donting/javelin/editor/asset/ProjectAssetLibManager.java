package cn.donting.javelin.editor.asset;


import cn.donting.javelin.core.assets.Asset;
import cn.donting.javelin.core.assets.AssetLibManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * @create: 2026/6/25
 * @Version 1.0
 **/
public class ProjectAssetLibManager extends AssetLibManager {
    private static final Logger log = LoggerFactory.getLogger(ProjectAssetLibManager.class);

    public ProjectAssetLibManager(File libDir) {
        super(libDir);
    }

    @Override
    public void loadIndex() {
        try {
            super.loadIndex();
        }catch (Exception ex){
            log.warn("加载索引文件失败:{}", ex.getMessage());
        }
    }

    // ═══ 资产热重载（Engine_AssetReload_Design.md §4.1，批次①） ═══

    /**
     * 仅查内存缓存（基类 {@code loadCache} 的公开面）：命中且类型相符返回实例，未命中/类型
     * 不符返回 null——<b>不落盘加载、不重建、不打日志</b>（loadGuid 会加载 Library 产物并打
     * 「加载 GUID」日志，故不适用）。供「换绑落点」等只想取已有共享实例的调用点用。
     */
    public <T extends Asset> T loadCache(String guid, Class<T> assetClass) {
        if (guid == null || guid.isBlank() || assetClass == null) {
            return null;
        }
        Asset cached = loadCache(guid);
        return assetClass.isInstance(cached) ? assetClass.cast(cached) : null;
    }

    /**
     * 丢弃 guid 的弱缓存条目（cache/guidAssetRefMap/trackedRefs 同步清），
     * 下次 loadGuid 走盘读新产物。与持有形态解耦——场景强引用照旧，换指针归重载协调器。
     */
    public void invalidate(String guid) {
        synchronized (this) {
            update();
            GuidAssetRef ref = guidAssetRefMap.remove(guid);
            if (ref != null) {
                trackedRefs.remove(ref);
            }
            cache.remove(guid);
        }
        log.info("资产缓存失效: guid={}", guid);
    }

    /**
     * invalidate + 重读盘上产物，返回重载前后的实例对（old = 缓存原实例可 null；
     * new = 盘上新实例，null = 产物损坏/加载失败，调用方保持旧引用——残档口径不抛）。
     */
    public <T extends Asset> ReloadResult<T> reload(String guid, Class<T> assetClass) {
        T old = assetClass.cast(loadCache(guid));
        invalidate(guid);
        T fresh;
        try {
            fresh = loadGuidInstance(guid, assetClass);
        } catch (Exception e) {
            log.warn("资产重载失败（保持旧引用）: guid={}", guid, e);
            return new ReloadResult<>(guid, old, null);
        }
        log.info("资产重载完成: guid={} old={} new={}", guid,
                old == null ? "null" : old.getClass().getSimpleName(),
                fresh.getClass().getSimpleName());
        return new ReloadResult<>(guid, old, fresh);
    }

    /** 重载结果值对（new null = 加载失败保持旧引用；无独立序列化协议，纯值对象） */
    public static final class ReloadResult<T extends Asset> {
        private final String guid;
        private final T old;
        private final T fresh;

        ReloadResult(String guid, T old, T fresh) {
            this.guid = guid;
            this.old = old;
            this.fresh = fresh;
        }

        public String getGuid() {
            return guid;
        }

        public T getOld() {
            return old;
        }

        public T getNew() {
            return fresh;
        }
    }
}
