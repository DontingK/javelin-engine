package com.javelin.editor.asset;


import com.javelin.core.assets.AssetLibManager;
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
}
