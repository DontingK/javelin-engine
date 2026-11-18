package cn.donting.javelin.core;

import cn.donting.javelin.rhi.desc.MemoryPreference;
import cn.donting.javelin.rhi.desc.TextureDesc;
import cn.donting.javelin.rhi.types.Format;
import cn.donting.javelin.rhi.types.SampleCount;
import cn.donting.javelin.rhi.types.TextureType;
import cn.donting.javelin.rhi.types.TextureUsage;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 纹理种类 × 形状约束单测（{@code RHI_Texture_Types_Design.md} §5 最后一条）：
 * 非法形状在描述构造期就抛 {@link IllegalArgumentException}，不等校验层。
 *
 * <p>纯逻辑，不建 GPU 资源。</p>
 */
public class TextureDescShapeTest {

    private static final Set<TextureUsage> USAGE = EnumSet.of(TextureUsage.SAMPLED);

    /** 3D 的第三维走 extent.depth，arrayLayers 必须为 1 */
    @Test
    public void type3DRejectsArrayLayers() {
        assertThrows(IllegalArgumentException.class, () -> new TextureDesc(
                TextureType.TYPE_3D, Format.R8_UNORM, 4, 4, 4,
                1, 2, SampleCount.COUNT_1, USAGE, MemoryPreference.deviceLocal()));
    }

    /** CUBE 恒六面 */
    @Test
    public void cubeRejectsNonSixLayers() {
        assertThrows(IllegalArgumentException.class, () -> new TextureDesc(
                TextureType.CUBE, Format.R8G8B8A8_UNORM, 4, 4, 1,
                1, 5, SampleCount.COUNT_1, USAGE, MemoryPreference.deviceLocal()));
    }

    /** CUBE 恒正方形 */
    @Test
    public void cubeRejectsNonSquare() {
        assertThrows(IllegalArgumentException.class, () -> new TextureDesc(
                TextureType.CUBE, Format.R8G8B8A8_UNORM, 4, 2, 1,
                1, 6, SampleCount.COUNT_1, USAGE, MemoryPreference.deviceLocal()));
    }

    /** 2D 的 depth 必须为 1 */
    @Test
    public void type2DRejectsDepth() {
        assertThrows(IllegalArgumentException.class, () -> new TextureDesc(
                TextureType.TYPE_2D, Format.R8_UNORM, 4, 4, 2,
                1, 1, SampleCount.COUNT_1, USAGE, MemoryPreference.deviceLocal()));
    }

    /** 2D 数组层数 < 2 直接拒绝（单层请用 texture2D） */
    @Test
    public void texture2DArrayRejectsSingleLayer() {
        assertThrows(IllegalArgumentException.class,
                () -> TextureDesc.texture2DArray(Format.R8G8B8A8_UNORM, 4, 4, 1, USAGE));
    }

    /** 合法形状不抛：3D 体数据 / 2D 数组 */
    @Test
    public void legalShapesPass() {
        TextureDesc.texture3D(Format.R8_UNORM, 16, 16, 16, USAGE);
        TextureDesc.texture2DArray(Format.R8G8B8A8_UNORM, 4, 4, 3, USAGE);
        TextureDesc.textureCube(Format.R8G8B8A8_UNORM, 4, USAGE);
    }
}
