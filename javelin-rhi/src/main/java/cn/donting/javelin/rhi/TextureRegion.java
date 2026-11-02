package cn.donting.javelin.rhi;

/**
 * 纹理更新区域。
 */
public final class TextureRegion {

    private final int x;
    private final int y;
    private final int z;
    private final int width;
    private final int height;
    private final int depth;
    private final int mipLevel;
    private final int baseLayer;
    private final int layerCount;

    public TextureRegion(int x, int y, int z,
                         int width, int height, int depth,
                         int mipLevel, int baseLayer) {
        this(x, y, z, width, height, depth, mipLevel, baseLayer, 1);
    }

    public TextureRegion(int x, int y, int z,
                         int width, int height, int depth,
                         int mipLevel, int baseLayer, int layerCount) {
        if (layerCount <= 0) {
            throw new IllegalArgumentException("layerCount must be positive, got: " + layerCount);
        }
        this.x = x;
        this.y = y;
        this.z = z;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.mipLevel = mipLevel;
        this.baseLayer = baseLayer;
        this.layerCount = layerCount;
    }

    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public int width() { return width; }
    public int height() { return height; }
    public int depth() { return depth; }
    public int mipLevel() { return mipLevel; }
    public int baseLayer() { return baseLayer; }
    public int layerCount() { return layerCount; }

    /** 全纹理 2D 更新 */
    public static TextureRegion whole2D(int width, int height) {
        return new TextureRegion(0, 0, 0, width, height, 1, 0, 0);
    }

    /**
     * cubemap 六面整拷（mip 0、全六层）：buffer 侧须六面连续紧密打包
     * （layer 顺序 +X/-X/+Y/-Y/+Z/-Z），单命令按 layer 顺序自动取址。
     */
    public static TextureRegion cubeFaces(int faceSize) {
        return new TextureRegion(0, 0, 0, faceSize, faceSize, 1, 0, 0, 6);
    }
}
