package com.javelin.core.render.mesh;

import com.javelin.rhi.desc.VertexLayout;
import com.javelin.rhi.types.VertexFormat;

import java.util.ArrayList;
import java.util.List;

/**
 * Mesh 构建器 —— 流式 API 构建顶点数据并生成 {@link Mesh}。
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * Mesh mesh = new MeshBuilder()
 *     .withPositions()
 *     .withNormals()
 *     .withUVs()
 *     .vertex(-1, -1, 0).normal(0, 0, 1).uv(0, 0)
 *     .vertex( 1, -1, 0).normal(0, 0, 1).uv(1, 0)
 *     .vertex( 0,  1, 0).normal(0, 0, 1).uv(0.5f, 1)
 *     .index(0, 1, 2)
 *     .build();
 * }</pre>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class MeshBuilder {

    // ═══════════════════════════════════════════════════════════════════════
    //  配置
    // ═══════════════════════════════════════════════════════════════════════

    private boolean hasPosition = true;   // 位置默认开启
    private boolean hasNormal;
    private boolean hasUV;
    private boolean hasColor;
    private boolean hasTangent;
    private boolean hasUV2;
    private boolean hasBone;

    // ═══════════════════════════════════════════════════════════════════════
    //  构建中数据
    // ═══════════════════════════════════════════════════════════════════════

    private final List<Vertex> vertices = new ArrayList<>();
    private final List<Integer> indices = new ArrayList<>();
    private Vertex currentVertex;

    // ═══════════════════════════════════════════════════════════════════════
    //  属性开关
    // ═══════════════════════════════════════════════════════════════════════

    public MeshBuilder withPositions() {
        this.hasPosition = true;
        return this;
    }

    public MeshBuilder withNormals() {
        this.hasNormal = true;
        return this;
    }

    public MeshBuilder withUVs() {
        this.hasUV = true;
        return this;
    }

    public MeshBuilder withColors() {
        this.hasColor = true;
        return this;
    }

    public MeshBuilder withTangents() {
        this.hasTangent = true;
        return this;
    }

    public MeshBuilder withUV2() {
        this.hasUV2 = true;
        return this;
    }

    public MeshBuilder withBones() {
        this.hasBone = true;
        return this;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  顶点 & 索引
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 开始一个新顶点（位置必须第一个设置）。
     */
    public MeshBuilder vertex(float x, float y, float z) {
        currentVertex = new Vertex();
        currentVertex.pos(x, y, z);
        vertices.add(currentVertex);
        return this;
    }

    public MeshBuilder normal(float x, float y, float z) {
        if (currentVertex != null) currentVertex.normal(x, y, z);
        return this;
    }

    public MeshBuilder uv(float u, float v) {
        if (currentVertex != null) currentVertex.uv(u, v);
        return this;
    }

    public MeshBuilder color(float r, float g, float b, float a) {
        if (currentVertex != null) currentVertex.color(r, g, b, a);
        return this;
    }

    public MeshBuilder tangent(float x, float y, float z, float w) {
        if (currentVertex != null) currentVertex.tangent(x, y, z, w);
        return this;
    }

    public MeshBuilder uv2(float u, float v) {
        if (currentVertex != null) currentVertex.uv2(u, v);
        return this;
    }

    public MeshBuilder bone(int i0, int i1, int i2, int i3) {
        if (currentVertex != null) currentVertex.boneIndices(i0, i1, i2, i3);
        return this;
    }

    public MeshBuilder boneWeight(float w0, float w1, float w2, float w3) {
        if (currentVertex != null) currentVertex.boneWeights(w0, w1, w2, w3);
        return this;
    }

    /**
     * 添加索引。
     */
    public MeshBuilder index(int... idx) {
        for (int i : idx) {
            indices.add(i);
        }
        return this;
    }

    /**
     * 从顶点数组批量构建。
     */
    public MeshBuilder vertices(Vertex[] verts) {
        for (Vertex v : verts) {
            vertices.add(v);
        }
        return this;
    }

    /**
     * 从 int[] 批量设置索引。
     */
    public MeshBuilder indices(int[] idx) {
        for (int i : idx) {
            indices.add(i);
        }
        return this;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  构建
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 构建 Mesh，生成按属性分离存储的顶点数据。
     */
    public Mesh build() {
        Mesh mesh = new Mesh();

        int vertexCount = vertices.size();

        // 提取各属性数据
        if (hasPosition) {
            float[] positions = new float[vertexCount * 3];
            for (int i = 0; i < vertexCount; i++) {
                Vertex v = vertices.get(i);
                positions[i * 3] = v.x;
                positions[i * 3 + 1] = v.y;
                positions[i * 3 + 2] = v.z;
            }
            mesh.setAttributeData(VertexAttribute.POSITION, positions);
        }

        if (hasNormal) {
            float[] normals = new float[vertexCount * 3];
            for (int i = 0; i < vertexCount; i++) {
                Vertex v = vertices.get(i);
                normals[i * 3] = v.nx;
                normals[i * 3 + 1] = v.ny;
                normals[i * 3 + 2] = v.nz;
            }
            mesh.setAttributeData(VertexAttribute.NORMAL, normals);
        }

        if (hasUV) {
            float[] uvs = new float[vertexCount * 2];
            for (int i = 0; i < vertexCount; i++) {
                Vertex v = vertices.get(i);
                uvs[i * 2] = v.u;
                uvs[i * 2 + 1] = v.v;
            }
            mesh.setAttributeData(VertexAttribute.TEX_COORD0, uvs);
        }

        if (hasColor) {
            float[] colors = new float[vertexCount * 4];
            for (int i = 0; i < vertexCount; i++) {
                Vertex v = vertices.get(i);
                colors[i * 4] = v.r;
                colors[i * 4 + 1] = v.g;
                colors[i * 4 + 2] = v.b;
                colors[i * 4 + 3] = v.a;
            }
            mesh.setAttributeData(VertexAttribute.COLOR, colors);
        }

        if (hasTangent) {
            float[] tangents = new float[vertexCount * 4];
            for (int i = 0; i < vertexCount; i++) {
                Vertex v = vertices.get(i);
                tangents[i * 4] = v.tx;
                tangents[i * 4 + 1] = v.ty;
                tangents[i * 4 + 2] = v.tz;
                tangents[i * 4 + 3] = v.tw;
            }
            mesh.setAttributeData(VertexAttribute.TANGENT, tangents);
        }

        if (hasUV2) {
            float[] uvs2 = new float[vertexCount * 2];
            for (int i = 0; i < vertexCount; i++) {
                Vertex v = vertices.get(i);
                uvs2[i * 2] = v.u2;
                uvs2[i * 2 + 1] = v.v2;
            }
            mesh.setAttributeData(VertexAttribute.TEX_COORD1, uvs2);
        }

        if (hasBone) {
            int[] boneIndices = new int[vertexCount * 4];
            float[] boneWeights = new float[vertexCount * 4];
            for (int i = 0; i < vertexCount; i++) {
                Vertex v = vertices.get(i);
                boneIndices[i * 4] = v.boneIndex0;
                boneIndices[i * 4 + 1] = v.boneIndex1;
                boneIndices[i * 4 + 2] = v.boneIndex2;
                boneIndices[i * 4 + 3] = v.boneIndex3;
                boneWeights[i * 4] = v.boneWeight0;
                boneWeights[i * 4 + 1] = v.boneWeight1;
                boneWeights[i * 4 + 2] = v.boneWeight2;
                boneWeights[i * 4 + 3] = v.boneWeight3;
            }
            mesh.setAttributeData(VertexAttribute.BONE_INDICES, boneIndices);
            mesh.setAttributeData(VertexAttribute.BONE_WEIGHTS, boneWeights);
        }

        // 索引
        if (!indices.isEmpty()) {
            int[] idx = new int[indices.size()];
            for (int i = 0; i < indices.size(); i++) {
                idx[i] = indices.get(i);
            }
            mesh.setIndexData(idx);
        }

        return mesh;
    }
}
