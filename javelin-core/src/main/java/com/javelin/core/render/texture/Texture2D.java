package com.javelin.core.render.texture;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.assets.AbsAsset;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.rhi.Texture;
import com.javelin.rhi.types.Format;

/**
 * 2D 纹理资产（对齐 Unity Texture2D 心智）：引用 {@link Image} 像素资产 + 采样/派生
 * 设置（filter/wrap/mipLevels）——同一像素资产可被多个 Texture2D 以不同设置引用，
 * 磁盘像素只存一份（三层拍板 2026-10-25，Engine_Texture_Design.md §1）。
 * 宽高/格式/像素经 image 委托读取，image 为 null 时访问即抛。
 *
 * <p>GPU 句柄由渲染侧首次绑定时上传后回填（{@link #setGpu}），与 Mesh 的缓冲同一
 * 生存期模型：资产 GC 不可达 → 句柄不可达 → RHI Cleaner 销毁（RHI 17 节），资产层
 * 不 dispose；序列化不落地 gpu 字段。内置占位常量（WHITE / BLACK / NORMAL）对齐
 * Unity whiteTexture / blackTexture / normalTexture（RHI 21.4 未赋槽解析）。</p>
 *
 * <p>字段非 final（红线 9）；只读靠 API 面保证。</p>
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

    /** 像素资产引用（Asset 引用走 guid 分流）；null = 未绑定 */
    private Image image;
    private int mipLevels = 1;
    private FilterMode filterMode = FilterMode.BILINEAR;
    private WrapMode wrapMode = WrapMode.REPEAT;
    /** GPU 句柄（渲染侧上传后回填；序列化不落地，随资产 GC 走 Cleaner） */
    private Texture gpu;

    /**
     * 反序列化用空构造（ReflectiveFactory 创建后 read 回写）。
     */
    public Texture2D() {
    }

    /**
     * 引用像素资产创建纹理（采样设置用默认值，setFilterMode/setWrapMode 再调）。
     */
    public Texture2D(Image image) {
        this.image = image;
    }

    /**
     * 像素资产引用；null = 未绑定（宽高/格式/像素访问即抛）。
     */
    public Image getImage() {
        return image;
    }

    /**
     * 换绑像素资产（上传冻结后禁止；换绑后 GPU 句柄按新资产重新上传）。
     */
    public void setImage(Image image) {
        checkMutable();
        this.image = image;
    }

    public int getWidth() {
        return requireImage().getWidth();
    }

    public int getHeight() {
        return requireImage().getHeight();
    }

    public Format getFormat() {
        return requireImage().getFormat();
    }

    public int getMipLevels() {
        return mipLevels;
    }

    /**
     * 像素本体（经 image 委托；上传前必须非空）。
     */
    public byte[] getPixels() {
        return requireImage().getPixels();
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

    /**
     * 序列化写出：guid/lastModified 由 AbsAsset 写；image 走 Asset 引用 guid 分流
     * （guid 空 = 图内嵌）；mipLevels/filterMode/wrapMode 直传（红线 10）。
     */
    @Override
    public void write(OutputCapsule output) {
        super.write(output);
        output.write("image", image);
        output.write("mipLevels", mipLevels);
        output.write("filterMode", filterMode);
        output.write("wrapMode", wrapMode);
    }

    /**
     * 序列化读入：只回写字段引用；枚举/mip 缺失不回写保字段初始默认
     * （filterMode=BILINEAR、wrapMode=REPEAT、mipLevels=1）。
     */
    @Override
    public void read(InputCapsule input) {
        super.read(input);
        image = input.readSerializable("image", Image.class);
        int mips = input.readInt("mipLevels");
        if (mips > 0) {
            mipLevels = mips;
        }
        FilterMode fm = input.readEnum("filterMode", FilterMode.class);
        if (fm != null) {
            filterMode = fm;
        }
        WrapMode wm = input.readEnum("wrapMode", WrapMode.class);
        if (wm != null) {
            wrapMode = wm;
        }
    }

    // ═══ 内置占位（Unity whiteTexture / blackTexture / normalTexture） ═══

    private static final class Placeholders {
        static final Texture2D WHITE = new Texture2D(Image.solid(0xFF, 0xFF, 0xFF, 0xFF));
        static final Texture2D BLACK = new Texture2D(Image.solid(0, 0, 0, 0xFF));
        /** 法线占位（Unity normalTexture / 本引擎 "bump" 约定：0.5, 0.5, 1.0） */
        static final Texture2D NORMAL = new Texture2D(Image.solid(0x80, 0x80, 0xFF, 0xFF));
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
        return new Texture2D(Image.solid(r, g, b, a));
    }

    private Image requireImage() {
        if (image == null) {
            throw new IllegalStateException("纹理未引用像素资源（setImage 先）");
        }
        return image;
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
