package com.javelin.editor.event;

import java.io.File;

/**
 * 资产热重载完成信号（Engine_AssetReload_Design §5.2-6）：重导产物换入场景对象图
 * + 渲染侧对账已执行后发布，载荷 = 资产 guid + 源文件——面板刷新消费方按源文件
 * 匹配自身编辑目标（材质面板刷新归批次④全量接入，本批先通信号链路）。
 *
 * <p>发布方在引擎线程（重载协调器 submitTask 帧首执行），Swing 消费方自行 EDT hop
 * （Editor_Swing_Design §7.3）。</p>
 *
 * @author javelin
 * @create 2026/10/27
 */
public final class AssetReloadedEvent implements EditorEvent {

    private final String guid;
    private final File sourceFile;

    public AssetReloadedEvent(String guid, File sourceFile) {
        this.guid = guid;
        this.sourceFile = sourceFile;
    }

    public String getGuid() {
        return guid;
    }

    public File getSourceFile() {
        return sourceFile;
    }
}
