package com.javelin.editor.event;

import java.io.File;

/**
 * 导入管线时机事件（AssetImportPipeline 在导入开始/完成/失败时发布）：
 * {@code phase} 区分开始/完成/失败，载荷 = 源文件 + guid——发布时快照，不携带可变的
 * ImportTask（总线契约要求事件发布后不可变）。
 *
 * <p>发布方在导入 worker 线程（publish 线程无关、入队即返回），消费方在总线泵线程串行回调。
 * START 当前无消费方，供进度类消费方后置接入。</p>
 *
 * @author javelin
 * @create 2026/10/30
 */
public final class AssetImportEvent implements EditorEvent {

    /** 导入时机 */
    public enum Phase {
        /** 导入开始 */
        START,
        /** 导入成功 */
        COMPLETE,
        /** 导入失败 */
        FAILED
    }

    private final Phase phase;
    private final File sourceFile;
    private final String guid;

    public AssetImportEvent(Phase phase, File sourceFile, String guid) {
        this.phase = phase;
        this.sourceFile = sourceFile;
        this.guid = guid;
    }

    public Phase getPhase() {
        return phase;
    }

    public File getSourceFile() {
        return sourceFile;
    }

    public String getGuid() {
        return guid;
    }
}
