package com.javelin.core.assets;

import com.javelin.core.serialize.binary.BinaryImporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 资源管理器
 *
 * <p>设计参考 JME AssetManager：</p>
 *
 * @author donting
 * @since 1.0
 */
public class AssetLibManager implements AssetManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(AssetLibManager.class);

    //GUID 表
    protected final Map<String, File> guidMap = new ConcurrentHashMap<>();
    //路径表，相对于libDir的相对路径, path,guid
    protected final Map<String, String> pathMap = new ConcurrentHashMap<>();
    private final File libDir;
    /**
     * GUID，缓存
     */
    protected final Map<String, GuidAssetRef> cache = new ConcurrentHashMap<>();

    protected final ReferenceQueue<Asset> refQueue = new ReferenceQueue<>();
    protected final Set<GuidAssetRef> trackedRefs = ConcurrentHashMap.newKeySet();
    protected final Map<String,GuidAssetRef> guidAssetRefMap = new HashMap<>();

    public AssetLibManager(File libDir) {
        this.libDir = libDir;
        loadIndex();
    }

    /**
     * 加载 Library 索引文件
     */
    protected synchronized void loadIndex() {
        File indexFile = new File(libDir, "index.asset");
        LOGGER.info("正在加载 Library 索引文件 {}", indexFile);
        if (!indexFile.exists()) {
            LOGGER.warn("Library 索引文件 {} 不存在", indexFile);
            return;
        }
        try (FileReader reader = new FileReader(indexFile)) {
            Properties props = new Properties();
            props.load(reader);
            for (String guid : props.stringPropertyNames()) {
                String sourcePath = props.getProperty(guid);
                File file = new File(libDir, guid + ".asset");
                guidMap.put(guid, file);
                pathMap.put(sourcePath, guid);
                LOGGER.debug("GUID: {} => {}", guid, file);
            }
            LOGGER.info("从 index.asset 加载 {} 条索引", guidMap.size());
        } catch (IOException e) {
            LOGGER.error("加载 index.asset 失败", e);
        }
    }

    private Asset loadCache(String guid) {
        update();
        GuidAssetRef guidAssetRef = cache.get(guid);
        if (guidAssetRef != null) {
            Asset guidAsset = guidAssetRef.get();
            if (guidAsset != null) {
                LOGGER.debug("GUID 缓存命中: {}=>{}", guid,guidAsset.getClass().getName());
                return guidAsset;
            }
        }
        return null;
    }

    @Override
    public <T extends Asset> T loadGuid(String guid, Class<T> assetClass) {
        Asset guidAsset = loadCache(guid);
        if(guidAsset != null){
            return (T) guidAsset;
        }
        File file = guidMap.get(guid);
        if(file == null){
            LOGGER.warn("GUID {} 未找到", guid);
            return null;
        }
        //加锁
        synchronized (this) {
            guidAsset = loadCache(guid);
            if(guidAsset != null){
                return (T) guidAsset;
            }
            T t = loadGuidInstance(guid, assetClass);
            GuidAssetRef ref = guidAssetRefMap.get(guid);
            cache.put(guid, ref);
            return t;
        }
    }

    @Override
    public <T extends Asset> T loadGuidInstance(String guid, Class<T> assetClass) {
        File file = guidMap.get(guid);
        if(file==null){
            LOGGER.warn("GUID:{} 资源不存在", guid);
            throw new RuntimeException("GUID:" + guid + " 资源不存在");
        }
        //文件加载资源
        BinaryImporter binaryImporter = new BinaryImporter(file, this);
        try {
            T asset = binaryImporter.importData(assetClass);
            GuidAssetRef ref = new GuidAssetRef(asset, guid, refQueue);
            trackedRefs.add(ref);
            guidAssetRefMap.put(guid,ref);
            return asset;
        } catch (Exception e) {
            LOGGER.info("GUID {} 加载失败", guid, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T extends Asset> T loadAsset(String path, Class<T> assetClass) {
        String guid = pathMap.get(path);
        if (guid != null) {
            return loadGuid(guid, assetClass);
        }
        LOGGER.warn("path {} 未找到", path);
        return null;
    }

    @Override
    public <T extends Asset> T loadAssetInstance(String path, Class<T> assetClass) {
        String guid = pathMap.get(path);
        return loadGuidInstance(guid,assetClass);
    }

    /**
     * 清理已被 GC 回收的缓存引用，防止僵尸条目累积
     * <p>建议定期调用（如场景切换时）</p>
     */
    public void update() {
        Reference<? extends Asset> poll = refQueue.poll();
        while (poll != null) {
            GuidAssetRef ref = (GuidAssetRef) poll;
            trackedRefs.remove(ref);
            guidAssetRefMap.remove(ref.guid);
            cache.values().remove(ref);
            poll = refQueue.poll();
        }
    }

    public File getLibDir() {
        return libDir;
    }

    /**
     * GuidAsset 弱引用
     */
    protected static class GuidAssetRef extends WeakReference<Asset> {
        private final String guid;

        public GuidAssetRef(Asset referent, String guid, ReferenceQueue<Asset> queue) {
            super(referent, queue);
            this.guid = guid;
        }
    }
}
