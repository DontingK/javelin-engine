package cn.donting.javelin.rhi;

import java.nio.ByteBuffer;

/**
 * 上传数据。
 * 对照设计文档第 25.4 节 / 第 26.6 节。
 */
public final class UploadData {

    private final ByteBuffer data;
    private final int mipLevels;

    private UploadData(ByteBuffer data, int mipLevels) {
        this.data = data;
        this.mipLevels = mipLevels;
    }

    public static UploadData of(ByteBuffer data, int mipLevels) {
        return new UploadData(data, mipLevels);
    }

    public static UploadData of(ByteBuffer data) {
        return new UploadData(data, 1);
    }

    public ByteBuffer data() { return data; }
    public int mipLevels() { return mipLevels; }
}
