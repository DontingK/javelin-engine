package cn.donting.javelin.rhi;

/**
 * 图形 Pass 在图中的句柄。
 * 对照设计文档第 26.5 节。
 */
public interface GraphicsPassBuilder {

    /** Pass 名（图内唯一） */
    String name();
}
