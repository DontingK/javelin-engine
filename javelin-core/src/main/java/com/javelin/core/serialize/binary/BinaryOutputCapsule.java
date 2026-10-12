package com.javelin.core.serialize.binary;

import com.javelin.core.assets.Asset;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.core.serialize.Serializable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * 二进制序列化输出实现
 *
 * @author javelin
 * @since 1.0
 */
public class BinaryOutputCapsule implements OutputCapsule, BinaryOutput {

    private static final byte TYPE_BYTE = 1;
    private static final byte TYPE_SHORT = 2;
    private static final byte TYPE_INT = 3;
    private static final byte TYPE_LONG = 4;
    private static final byte TYPE_FLOAT = 5;
    private static final byte TYPE_DOUBLE = 6;
    private static final byte TYPE_BOOLEAN = 7;
    private static final byte TYPE_CHAR = 8;
    private static final byte TYPE_STRING = 9;
    private static final byte TYPE_OBJECT = 10;
    private static final byte TYPE_ENUM = 11;
    private static final byte TYPE_ASSET = 12;
    private static final byte TYPE_VECTOR3F = 13;
    private static final byte TYPE_QUATERNIONF = 14;
    private static final byte TYPE_MATRIX4F = 15;

    private final Serializable value;
    private final BinaryExporter exporter;
    private final int id;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final DataOutputStream dataOut = new DataOutputStream(buffer);
    private final Set<String> keys = new HashSet<>();
    private int fieldCount = 0;

    public BinaryOutputCapsule(int id, Serializable value, BinaryExporter exporter) {
        this.id = id;
        this.value = value;
        this.exporter = exporter;
    }

    private void writeKeyAndType(String key, byte type) {
        if (!keys.add(key)) {
            throw new IllegalStateException("重复的序列化字段 key: " + key + "，类型: " + value.getClass().getName());
        }
        try {
            writeString(key);
            dataOut.writeByte(type);
            fieldCount++;
        } catch (IOException e) {
            throw new RuntimeException("写入字段头失败", e);
        }
    }

    private void writeString(String value) throws IOException {
        if (value == null) {
            dataOut.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        dataOut.writeInt(bytes.length);
        dataOut.write(bytes);
    }

    @Override
    public void write(String key, byte value) {
        writeKeyAndType(key, TYPE_BYTE);
        try {
            dataOut.writeByte(value);
        } catch (IOException e) {
            throw new RuntimeException("写入 byte 失败", e);
        }
    }

    @Override
    public void write(String key, short value) {
        writeKeyAndType(key, TYPE_SHORT);
        try {
            dataOut.writeShort(value);
        } catch (IOException e) {
            throw new RuntimeException("写入 short 失败", e);
        }
    }

    @Override
    public void write(String key, int value) {
        writeKeyAndType(key, TYPE_INT);
        try {
            dataOut.writeInt(value);
        } catch (IOException e) {
            throw new RuntimeException("写入 int 失败", e);
        }
    }

    @Override
    public void write(String key, long value) {
        writeKeyAndType(key, TYPE_LONG);
        try {
            dataOut.writeLong(value);
        } catch (IOException e) {
            throw new RuntimeException("写入 long 失败", e);
        }
    }

    @Override
    public void write(String key, float value) {
        writeKeyAndType(key, TYPE_FLOAT);
        try {
            dataOut.writeFloat(value);
        } catch (IOException e) {
            throw new RuntimeException("写入 float 失败", e);
        }
    }

    @Override
    public void write(String key, double value) {
        writeKeyAndType(key, TYPE_DOUBLE);
        try {
            dataOut.writeDouble(value);
        } catch (IOException e) {
            throw new RuntimeException("写入 double 失败", e);
        }
    }

    @Override
    public void write(String key, boolean value) {
        writeKeyAndType(key, TYPE_BOOLEAN);
        try {
            dataOut.writeBoolean(value);
        } catch (IOException e) {
            throw new RuntimeException("写入 boolean 失败", e);
        }
    }

    @Override
    public void write(String key, char value) {
        writeKeyAndType(key, TYPE_CHAR);
        try {
            dataOut.writeChar(value);
        } catch (IOException e) {
            throw new RuntimeException("写入 char 失败", e);
        }
    }

    @Override
    public void write(String key, String value) {
        if (value == null) {
            return;
        }
        writeKeyAndType(key, TYPE_STRING);
        try {
            writeString(value);
        } catch (IOException e) {
            throw new RuntimeException("写入 String 失败", e);
        }
    }

    @Override
    public void write(String key, Serializable value) {
        if (value == null) {
            return;
        }
        try {
            // 根对象被图内字段引用（如 Node.scene → GameScene）走对象 id 引用
            // 而非 GUID（图内根引用 vs 外部资产语义，2026-10-02 拍板，见 BinaryExporter.isRoot）。
            // guid 为 null 的非根 Asset（内嵌材质等运行期实例）同走对象 id——与文本侧
            // TextOutputCapsule 三条件同款（guid 非空才算外部引用），否则 TYPE_ASSET 写
            // null guid 读侧解不回（打包游戏"有效MeshRenderer=0"真凶，2026-10-12 实锤）
            if (value instanceof Asset asset && asset.getGuid() != null && !exporter.isRoot(asset)) {
                writeKeyAndType(key, TYPE_ASSET);
                writeString(asset.getGuid());
                return;
            }
            writeKeyAndType(key, TYPE_OBJECT);
            BinaryOutputCapsule output = exporter.getOutput(value);
            dataOut.writeInt(output != null ? output.getId() : BinaryExporter.NULL_OBJECT_ID);
        } catch (IOException e) {
            throw new RuntimeException("写入对象引用失败", e);
        }
    }

    @Override
    public void write(String key, Enum value) {
        if (value == null) {
            return;
        }
        writeKeyAndType(key, TYPE_ENUM);
        try {
            writeString(value.name());
        } catch (IOException e) {
            throw new RuntimeException("写入 Enum 失败", e);
        }
    }

    @Override
    public void write(String key, byte[] value) {
        if (value == null) {
            return;
        }
        writeArrayRef(key, value);
    }

    @Override
    public void write(String key, short[] value) {
        if (value == null) {
            return;
        }
        writeArrayRef(key, value);
    }

    @Override
    public void write(String key, int[] value) {
        if (value == null) {
            return;
        }
        writeArrayRef(key, value);
    }

    @Override
    public void write(String key, long[] value) {
        if (value == null) {
            return;
        }
        writeArrayRef(key, value);
    }

    @Override
    public void write(String key, float[] value) {
        if (value == null) {
            return;
        }
        writeArrayRef(key, value);
    }

    @Override
    public void write(String key, double[] value) {
        if (value == null) {
            return;
        }
        writeArrayRef(key, value);
    }

    @Override
    public void write(String key, boolean[] value) {
        if (value == null) {
            return;
        }
        writeArrayRef(key, value);
    }

    @Override
    public void write(String key, char[] value) {
        if (value == null) {
            return;
        }
        writeArrayRef(key, value);
    }

    @Override
    public void write(String key, String[] value) {
        if (value == null) {
            return;
        }
        writeArrayRef(key, value);
    }

    @Override
    public void write(String key, Serializable[] value) {
        if (value == null) {
            return;
        }
        writeArrayRef(key, value);
    }

    @Override
    public void write(String key, Enum[] value) {
        if (value == null) {
            return;
        }
        writeArrayRef(key, value);
    }

    @Override
    public <T extends Serializable> void write(String key, ArrayList<T> values) {
        if (values == null) {
            return;
        }
        writeKeyAndType(key, TYPE_OBJECT);
        try {
            BinaryArrayListOutput output = exporter.getArrayListOutput(values);
            dataOut.writeInt(output != null ? output.getId() : BinaryExporter.NULL_OBJECT_ID);
        } catch (IOException e) {
            throw new RuntimeException("写入 ArrayList 引用失败", e);
        }
    }

    @Override
    public void writeArrayString(String key, ArrayList<String> values) {
        if (values == null) {
            return;
        }
        writeKeyAndType(key, TYPE_OBJECT);
        try {
            BinaryArrayListOutput output = exporter.getArrayListOutput(values);
            dataOut.writeInt(output != null ? output.getId() : BinaryExporter.NULL_OBJECT_ID);
        } catch (IOException e) {
            throw new RuntimeException("写入 ArrayList<String> 引用失败", e);
        }
    }

    @Override
    public void writeEnumList(String key, ArrayList<? extends Enum<?>> values) {
        if (values == null) {
            return;
        }
        writeKeyAndType(key, TYPE_OBJECT);
        try {
            BinaryArrayListOutput output = exporter.getArrayListOutput(values);
            dataOut.writeInt(output != null ? output.getId() : BinaryExporter.NULL_OBJECT_ID);
        } catch (IOException e) {
            throw new RuntimeException("写入 ArrayList<Enum> 引用失败", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  JOML 数学类型（二进制紧凑格式）
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void write(String key, Vector3f value) {
        if (value == null) {
            return;
        }
        writeKeyAndType(key, TYPE_VECTOR3F);
        try {
            dataOut.writeFloat(value.x);
            dataOut.writeFloat(value.y);
            dataOut.writeFloat(value.z);
        } catch (IOException e) {
            throw new RuntimeException("写入 Vector3f 失败", e);
        }
    }

    @Override
    public void write(String key, Quaternionf value) {
        if (value == null) {
            return;
        }
        writeKeyAndType(key, TYPE_QUATERNIONF);
        try {
            dataOut.writeFloat(value.x);
            dataOut.writeFloat(value.y);
            dataOut.writeFloat(value.z);
            dataOut.writeFloat(value.w);
        } catch (IOException e) {
            throw new RuntimeException("写入 Quaternionf 失败", e);
        }
    }

    @Override
    public void write(String key, Matrix4f value) {
        if (value == null) {
            return;
        }
        writeKeyAndType(key, TYPE_MATRIX4F);
        try {
            // 列优先写入 16 个 float
            dataOut.writeFloat(value.m00());
            dataOut.writeFloat(value.m01());
            dataOut.writeFloat(value.m02());
            dataOut.writeFloat(value.m03());
            dataOut.writeFloat(value.m10());
            dataOut.writeFloat(value.m11());
            dataOut.writeFloat(value.m12());
            dataOut.writeFloat(value.m13());
            dataOut.writeFloat(value.m20());
            dataOut.writeFloat(value.m21());
            dataOut.writeFloat(value.m22());
            dataOut.writeFloat(value.m23());
            dataOut.writeFloat(value.m30());
            dataOut.writeFloat(value.m31());
            dataOut.writeFloat(value.m32());
            dataOut.writeFloat(value.m33());
        } catch (IOException e) {
            throw new RuntimeException("写入 Matrix4f 失败", e);
        }
    }

    private void writeArrayRef(String key, Object array) {
        writeKeyAndType(key, TYPE_OBJECT);
        try {
            BinaryArrayOutput output = exporter.getArrayOutput(array);
            dataOut.writeInt(output != null ? output.getId() : BinaryExporter.NULL_OBJECT_ID);
        } catch (IOException e) {
            throw new RuntimeException("写入数组引用失败", e);
        }
    }

    @Override
    public void collect() {
        value.write(this);
    }

    @Override
    public void writeTo(BinaryStreamOutput out) {
        out.write(fieldCount);
        out.write(buffer.toByteArray());
    }

    @Override
    public Object getValue() {
        return value;
    }

    @Override
    public int getId() {
        return id;
    }
}
