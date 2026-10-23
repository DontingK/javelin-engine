package com.javelin.editor.debug;

import com.javelin.core.math.Color;
import com.javelin.core.physics.collision.Aabb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * DebugDrawLine 数据面（Editor_DebugLine_Design §3.4）：CPU 侧线段拼装。
 *
 * <p>顶点 = {@code pos vec3 + RGBA8} = 16B/顶点、每线 2 顶点（LINE_LIST）；
 * 预分配 ByteBuffer 复用（热路径零分配）。容量 {@value #MAX_LINES} 线，超限
 * 截断 + 一次性 warn（运行期扩容后置）。{@link #data()} 返回已拼装顶点
 * （flip 态），{@link DebugLinePass} 每帧双帧槽 buffer.update 直传。</p>
 *
 * @author javelin
 * @create 2026/10/23
 */
final class DebugLineBatch {

    private static final Logger log = LoggerFactory.getLogger(DebugLineBatch.class);

    /** 容量上限（线数；8192×2 顶点×16B = 256KB×2 帧槽） */
    static final int MAX_LINES = 8192;
    /** 顶点字节数：pos vec3 (12B) + RGBA8 (4B) */
    static final int VERTEX_STRIDE = 16;

    private final ByteBuffer verts = ByteBuffer.allocateDirect(MAX_LINES * 2 * VERTEX_STRIDE)
            .order(ByteOrder.LITTLE_ENDIAN);
    private int vertexCount;
    private boolean overflowWarned;

    void clear() {
        verts.clear();
        vertexCount = 0;
    }

    void addLine(float x0, float y0, float z0, float x1, float y1, float z1, Color color) {
        if (vertexCount >= MAX_LINES * 2) {
            if (!overflowWarned) {
                overflowWarned = true;
                log.warn("DebugLineBatch 截断：线数超上限 {}（超出部分本帧不画，一次告警）", MAX_LINES);
            }
            return;
        }
        putVertex(x0, y0, z0, color);
        putVertex(x1, y1, z1, color);
    }

    void addAabb(Aabb box, Color color) {
        float minX = box.getMin().x, minY = box.getMin().y, minZ = box.getMin().z;
        float maxX = box.getMax().x, maxY = box.getMax().y, maxZ = box.getMax().z;
        // 底面 4 边 + 顶面 4 边 + 立柱 4 边
        addLine(minX, minY, minZ, maxX, minY, minZ, color);
        addLine(maxX, minY, minZ, maxX, minY, maxZ, color);
        addLine(maxX, minY, maxZ, minX, minY, maxZ, color);
        addLine(minX, minY, maxZ, minX, minY, minZ, color);
        addLine(minX, maxY, minZ, maxX, maxY, minZ, color);
        addLine(maxX, maxY, minZ, maxX, maxY, maxZ, color);
        addLine(maxX, maxY, maxZ, minX, maxY, maxZ, color);
        addLine(minX, maxY, maxZ, minX, maxY, minZ, color);
        addLine(minX, minY, minZ, minX, maxY, minZ, color);
        addLine(maxX, minY, minZ, maxX, maxY, minZ, color);
        addLine(maxX, minY, maxZ, maxX, maxY, maxZ, color);
        addLine(minX, minY, maxZ, minX, maxY, maxZ, color);
    }

    private void putVertex(float x, float y, float z, Color color) {
        verts.putFloat(x).putFloat(y).putFloat(z);
        verts.put((byte) (color.r * 255))
                .put((byte) (color.g * 255))
                .put((byte) (color.b * 255))
                .put((byte) (color.a * 255));
        vertexCount++;
    }

    /** 顶点数（= 线数×2） */
    int vertexCount() {
        return vertexCount;
    }

    boolean isEmpty() {
        return vertexCount == 0;
    }

    /**
     * 已拼装顶点数据（duplicate 视图 flip 态：position=0、limit=vertexCount×stride），
     * 供 {@code Buffer.update(0, data)} 直传——视图独立 pos/limit 不污染写入游标。
     */
    ByteBuffer data() {
        ByteBuffer view = verts.duplicate();
        view.flip();
        return view;
    }
}
