package com.javelin.core.render.texture;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.assets.AbsAsset;
import com.javelin.rhi.Texture;
import com.javelin.rhi.types.Format;

import java.nio.ByteBuffer;

/**
 * 2D 纹理资产（对齐 Unity Texture2D 心智）：CPU 像素数据持有者。
 *
 * <p>构造/填充均为 CPU 侧操作，device 无关；GPU 句柄由渲染侧首次绑定时
 * 上传后回填（{@link #setGpu}），与 Mesh 的缓冲同一生存期模型：
 * 资产 GC 不可达 → 句柄不可达 → RHI Cleaner 销毁（RHI 17 节），资产层不 dispose。
 * 采样参数（filter/wrap）在此声明，渲染侧建 Sampler 时消费。</p>
 *
 * <p>内置占位常量（WHITE / BLACK / NORMAL）对齐 Unity 的
 * whiteTexture / blackTexture / normalTexture：上传既已推迟到渲染侧，
 * 占位纹理无需 Device 即可静态可用；未赋值贴图槽的解析见 RHI 21.4 节约定。</p>
 *
 * <p>字段非 final：序列化反序列化需回写（AGENTS.md 红线 9）；
 * 只读靠 API 面保证（无公开修改方法之外的破坏路径）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public class Texture2D extends AbsAsset {

    /**
     * 采样过滤模式（渲染侧建 Sampler 用）。
     */
    public enum FilterMode {
        POINT,
        BILINEAR,
        TRILINEAR
    }

    /**
     * 寻址环绕模式（渲染侧建 Sampler 用）。
     */
    public enum WrapMode {
        REPEAT,
        CLAMP
    }

    private int width;
    private int height;
    private Format format;
    private int mipLevels;
    private FilterMode filterMode = FilterMode.BILINEAR;
    private WrapMode wrapMode = WrapMode.REPEAT;
    /** CPU 像素副本（紧密打包，format 决定每像素字节数）；null = 未填充 */
    private ByteBuffer pixels;
    /** GPU 句柄（渲染侧上传后回填；序列化不落地，随资产 GC 走 Cleaner） */
    private Texture gpu;

    /**
     * 反序列化用空构造（ReflectiveFactory 创建后 read 回写）。
     */
    public Texture2D() {
    }

    /**
     * 创建空纹理（Unity {@code new Texture2D(w, h, format, mipCount)}）。
     */
    public Texture2D(int width, int height, Format format, int mipLevels) {
        this.width = width;
        this.height = height;
        this.format = format;
        this.mipLevels = mipLevels;
    }

    /**
     * 创建并填充像素（Unity SetPixels 语义；引用专用缓冲，勿再读写原缓冲）。
     */
    public Texture2D(int width, int height, Format format, int mipLevels, ByteBuffer pixels) {
        this(width, height, format, mipLevels);
        this.pixels = pixels;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public Format getFormat() {
        return format;
    }

    public int getMipLevels() {
        return mipLevels;
    }

    public FilterMode getFilterMode() {
        return filterMode;
    }

    public void setFilterMode(FilterMode filterMode) {
        checkMutable();
        this.filterMode = filterMode;
    }

    public WrapMode getWrapMode() {
        return wrapMode;
    }

    public void setWrapMode(WrapMode wrapMode) {
        checkMutable();
        this.wrapMode = wrapMode;
    }

    /**
     * CPU 像素副本（紧密打包；上传前必须非空，渲染侧消费后是否丢弃见桥接实现）。
     */
    public ByteBuffer getPixels() {
        return pixels;
    }

    /**
     * 填充像素（Unity SetPixels 语义；上传前调用，引用专用缓冲）。
     */
    public void setPixels(ByteBuffer pixels) {
        checkMutable();
        this.pixels = pixels;
    }

    /**
     * GPU 句柄（渲染侧绑描述符用）；null = 尚未上传。
     */
    Texture getGpu() {
        return gpu;
    }

    /**
     * 回填 GPU 句柄（渲染侧上传桥接调用，勿手动调用；序列化不落地本字段）。
     */
    void setGpu(Texture gpu) {
        this.gpu = gpu;
    }

    // ═══ 内置占位（Unity whiteTexture / blackTexture / normalTexture） ═══

    private static final class Placeholders {
        static final Texture2D WHITE = solid(0xFF, 0xFF, 0xFF, 0xFF);
        static final Texture2D BLACK = solid(0, 0, 0, 0xFF);
        /** 法线占位（Unity normalTexture / 本引擎 "bump" 约定：0.5, 0.5, 1.0） */
        static final Texture2D NORMAL = solid(0x80, 0x80, 0xFF, 0xFF);
    }

    public static Texture2D whiteTexture() {
        return Placeholders.WHITE;
    }

    public static Texture2D blackTexture() {
        return Placeholders.BLACK;
    }

    public static Texture2D normalTexture() {
        return Placeholders.NORMAL;
    }

    /**
     * 1×1 纯色纹理（占位贴图工厂；device 无关）。
     */
    public static Texture2D solid(int r, int g, int b, int a) {
        ByteBuffer pixel = ByteBuffer.allocateDirect(4);
        pixel.put((byte) r).put((byte) g).put((byte) b).put((byte) a).flip();
        return new Texture2D(1, 1, Format.R8G8B8A8_UNORM, 1, pixel);
    }

    /**
     * 冻结语义（拍板 2026-09-25，同 Mesh）：上传后资产不可变，要改就新建。
     * 动态顶点/像素更新（骨骼、粒子、物理形变）将来走专门的动态缓冲路径，
     * 不经此处重传。
     */
    private void checkMutable() {
        if (gpu != null) {
            throw new IllegalStateException("Texture2D 已上传冻结，不可再修改（要改请新建资产）");
        }
    }
}
