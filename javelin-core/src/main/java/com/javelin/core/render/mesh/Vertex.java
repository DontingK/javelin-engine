package com.javelin.core.render.mesh;

import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * 单个顶点数据 —— 包含位置、法线、UV、颜色、切线等常用属性。
 *
 * <p>所有属性可选，未设置的属性在构建时以默认值填充。</p>
 *
 * <p>使用 {@link MeshBuilder} 或 {@link Mesh#setVertices(Vertex[])} 构建网格。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class Vertex {

    /** 位置 (xyz) */
    public float x, y, z;

    /** 法线 (xyz) */
    public float nx, ny, nz;

    /** UV (st) */
    public float u, v;

    /** 颜色 (rgba)，范围 [0,1] */
    public float r, g, b, a;

    /** 切线 (xyzw)，w = 1 或 -1 表示手性 */
    public float tx, ty, tz, tw;

    /** 第二套 UV */
    public float u2, v2;

    /** 骨骼索引 (4个) */
    public int boneIndex0, boneIndex1, boneIndex2, boneIndex3;

    /** 骨骼权重 (4个) */
    public float boneWeight0, boneWeight1, boneWeight2, boneWeight3;

    public Vertex() {
        this.r = 1.0f;
        this.g = 1.0f;
        this.b = 1.0f;
        this.a = 1.0f;
        this.tw = 1.0f;
    }

    public Vertex(float x, float y, float z) {
        this();
        this.x = x;
        this.y = y;
        this.z = z;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  链式设置
    // ═══════════════════════════════════════════════════════════════════════

    public Vertex pos(float x, float y, float z) {
        this.x = x; this.y = y; this.z = z;
        return this;
    }

    public Vertex pos(Vector3f p) {
        return pos(p.x, p.y, p.z);
    }

    public Vertex normal(float x, float y, float z) {
        this.nx = x; this.ny = y; this.nz = z;
        return this;
    }

    public Vertex normal(Vector3f n) {
        return normal(n.x, n.y, n.z);
    }

    public Vertex uv(float u, float v) {
        this.u = u; this.v = v;
        return this;
    }

    public Vertex uv(Vector2f uv) {
        return uv(uv.x, uv.y);
    }

    public Vertex uv2(float u, float v) {
        this.u2 = u; this.v2 = v;
        return this;
    }

    public Vertex color(float r, float g, float b, float a) {
        this.r = r; this.g = g; this.b = b; this.a = a;
        return this;
    }

    public Vertex color(int rgba) {
        this.r = ((rgba >> 24) & 0xFF) / 255.0f;
        this.g = ((rgba >> 16) & 0xFF) / 255.0f;
        this.b = ((rgba >> 8) & 0xFF) / 255.0f;
        this.a = (rgba & 0xFF) / 255.0f;
        return this;
    }

    public Vertex tangent(float x, float y, float z, float w) {
        this.tx = x; this.ty = y; this.tz = z; this.tw = w;
        return this;
    }

    public Vertex tangent(Vector4f t) {
        return tangent(t.x, t.y, t.z, t.w);
    }

    public Vertex boneIndices(int i0, int i1, int i2, int i3) {
        this.boneIndex0 = i0; this.boneIndex1 = i1;
        this.boneIndex2 = i2; this.boneIndex3 = i3;
        return this;
    }

    public Vertex boneWeights(float w0, float w1, float w2, float w3) {
        this.boneWeight0 = w0; this.boneWeight1 = w1;
        this.boneWeight2 = w2; this.boneWeight3 = w3;
        return this;
    }
}
