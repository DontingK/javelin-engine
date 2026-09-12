package com.javelin.editor.serializable;


import com.javelin.core.assets.Asset;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.core.serialize.Serializable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public class TextOutputCapsule implements OutputCapsule {

    private final Serializable value;
    private final TextExporter exporter;
    private final Map<String, String> entries = new LinkedHashMap<>();

    public TextOutputCapsule(Serializable value, TextExporter exporter) {
        this.value = value;
        this.exporter = exporter;
    }

    public void write() {
        value.write(this);
    }

    public void writeTo(Appendable out, int id) throws IOException {
        out.append("[").append(String.valueOf(id)).append(":").append(value.getClass().getName()).append("]\n");
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            out.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
        }
    }

    @Override
    public void write(String key, byte value) {
        entries.put(key, String.valueOf(value));
    }

    @Override
    public void write(String key, short value) {
        entries.put(key, String.valueOf(value));
    }

    @Override
    public void write(String key, int value) {
        entries.put(key, String.valueOf(value));
    }

    @Override
    public void write(String key, long value) {
        entries.put(key, String.valueOf(value));
    }

    @Override
    public void write(String key, float value) {
        entries.put(key, String.valueOf(value));
    }

    @Override
    public void write(String key, double value) {
        entries.put(key, String.valueOf(value));
    }

    @Override
    public void write(String key, boolean value) {
        entries.put(key, String.valueOf(value));
    }

    @Override
    public void write(String key, char value) {
        entries.put(key, String.valueOf(value));
    }

    @Override
    public void write(String key, String value) {
        if (value == null) {
            entries.put(key, "@null");
            return;
        }
        entries.put(key, TextExporter.escape(value));
    }

    @Override
    public void write(String key, Serializable value) {
        if (value == null) {
            entries.put(key, "@null");
            return;
        }
        if (value instanceof Asset ga && ga.getGuid() != null) {
            entries.put(key, "@guid:" + ga.getGuid());
            return;
        }
        int id = exporter.getObjectId(value);
        entries.put(key, "@ref:" + id);
    }

    @Override
    public void write(String key, Enum value) {
        if (value == null) {
            entries.put(key, "@null");
            return;
        }
        entries.put(key, value.name());
    }

    @Override
    public void write(String key, byte[] value) {
        writeArray(key, value);
    }

    @Override
    public void write(String key, short[] value) {
        writeArray(key, value);
    }

    @Override
    public void write(String key, int[] value) {
        writeArray(key, value);
    }

    @Override
    public void write(String key, long[] value) {
        writeArray(key, value);
    }

    @Override
    public void write(String key, float[] value) {
        writeArray(key, value);
    }

    @Override
    public void write(String key, double[] value) {
        writeArray(key, value);
    }

    @Override
    public void write(String key, boolean[] value) {
        writeArray(key, value);
    }

    @Override
    public void write(String key, char[] value) {
        writeArray(key, value);
    }

    @Override
    public void write(String key, String[] value) {
        writeArray(key, value);
    }

    @Override
    public void write(String key, Serializable[] value) {
        writeArray(key, value);
    }

    @Override
    public void write(String key, Enum[] value) {
        writeArray(key, value);
    }

    @Override
    public <T extends Serializable> void write(String key, ArrayList<T> values) {
        if (values == null) {
            entries.put(key, "@null");
            return;
        }
        int id = exporter.getObjectId(values);
        entries.put(key, "@ref:" + id);
    }

    @Override
    public void writeArrayString(String key, ArrayList<String> values) {
        if (values == null) {
            entries.put(key, "@null");
            return;
        }
        int id = exporter.getObjectId(values);
        entries.put(key, "@ref:" + id);
    }

    @Override
    public void writeEnumList(String key, ArrayList<? extends Enum<?>> values) {

    }

    private void writeArray(String key, Object array) {
        if (array == null) {
            entries.put(key, "@null");
            return;
        }
        int id = exporter.getObjectId(array);
        entries.put(key, "@ref:" + id);
    }

    @Override
    public void write(String key, Vector3f value) {
        if (value == null) {
            entries.put(key, "@null");
            return;
        }
        entries.put(key, value.x + "," + value.y + "," + value.z);
    }

    @Override
    public void write(String key, Quaternionf value) {
        if (value == null) {
            entries.put(key, "@null");
            return;
        }
        entries.put(key, value.x + "," + value.y + "," + value.z + "," + value.w);
    }

    @Override
    public void write(String key, Matrix4f value) {
        if (value == null) {
            entries.put(key, "@null");
            return;
        }
        // 列优先存储 16 个 float
        entries.put(key,
            value.m00() + "," + value.m01() + "," + value.m02() + "," + value.m03() + "," +
            value.m10() + "," + value.m11() + "," + value.m12() + "," + value.m13() + "," +
            value.m20() + "," + value.m21() + "," + value.m22() + "," + value.m23() + "," +
            value.m30() + "," + value.m31() + "," + value.m32() + "," + value.m33()
        );
    }
}
