package cn.donting.javelin.core.render.ddgi;

import cn.donting.javelin.core.render.mesh.Mesh;

/**
 * 体素静态集签名（Engine_DDGI_Design.md §4.1 / BUG-13 结论，P4-05 批 B1 残留②）。
 *
 * <p><b>为什么不是裸计数</b>：退役的体素 AO 只比 {@code renderers().size()}，于是「同数量的
 * 物体被换掉/被移动」不会触发重建，体素图里留着幽灵几何（幽灵暗角）。签名把**每个参与
 * 体素化的物体身份**（网格实例 + 世界矩阵 + 基色）都拌进一个 64 位摘要，集合一动签名就变。</p>
 *
 * <p><b>世界矩阵量化</b>：按 1e-4 取整后再拌，避免矩阵末位抖动把签名抖成「每帧都变」
 * （摄像机矩阵每帧重算、编辑器拖拽时的浮点噪声都属这类）。位移 0.1mm 以内视为没动——对
 * 体素分辨率（≥ 十厘米级）完全分辨不出。</p>
 *
 * <p><b>不拌的</b>：材质贴图内容（热重载另有对账通道）、阴影/接收阴影开关（与体素无关）。</p>
 */
public final class StaticSetSignature {

    private final int entryCount;
    private final long digest;

    private StaticSetSignature(int entryCount, long digest) {
        this.entryCount = entryCount;
        this.digest = digest;
    }

    /** 参与体素化的物体数（网格实例 × 世界变换去重后的条数） */
    public int entryCount() {
        return entryCount;
    }

    public long digest() {
        return digest;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StaticSetSignature)) {
            return false;
        }
        StaticSetSignature other = (StaticSetSignature) o;
        return entryCount == other.entryCount && digest == other.digest;
    }

    @Override
    public int hashCode() {
        return 31 * entryCount + Long.hashCode(digest);
    }

    @Override
    public String toString() {
        return "StaticSetSignature{entries=" + entryCount
                + ", digest=" + Long.toHexString(digest) + "}";
    }

    /** 新建累加器（调用方按收集顺序逐条 {@code add}） */
    public static Builder builder() {
        return new Builder();
    }

    /** 逐条累加器（收集期一遍走完，不建中间集合） */
    public static final class Builder {

        private int entryCount;
        private long digest = 0x9E3779B97F4A7C15L;
        /**
         * 追加一个参与体素化的物体。
         *
         * @param mesh   网格实例（按实例身份取哈希：资产热重载换实例即算「集合变了」）
         * @param world  世界矩阵（16 个 float，行/列序由引擎约定，本类只当数据看）
         * @param albedo 基色 rgb（3 个 float；基色变了要重建，否则反弹颜色是旧的）
         */
        public Builder add(Mesh mesh, float[] world, float[] albedo) {
            if (mesh == null || world == null || world.length < 16 || albedo == null || albedo.length < 3) {
                throw new IllegalArgumentException("mesh / world(16) / albedo(3) 不完整");
            }
            entryCount++;
            digest = mix(digest, System.identityHashCode(mesh));
            for (int i = 0; i < 16; i++) {
                digest = mix(digest, quantize(world[i]));
            }
            for (int i = 0; i < 3; i++) {
                digest = mix(digest, quantize(albedo[i]));
            }
            digest = mix(digest, entryCount);
            return this;
        }

        public StaticSetSignature build() {
            return new StaticSetSignature(entryCount, digest);
        }

        /** 1e-4 量化（矩阵/基色末位抖动不触发重建） */
        private static int quantize(float value) {
            return Math.round(value * 10000f);
        }

        /** 64 位拌入（FNV-1a 变体：乘奇数 + 异或移位，够用且无分配） */
        private static long mix(long digest, int value) {
            long h = digest ^ (value & 0xFFFFFFFFL);
            h *= 0x100000001B3L;
            h ^= h >>> 29;
            return h;
        }
    }
}
