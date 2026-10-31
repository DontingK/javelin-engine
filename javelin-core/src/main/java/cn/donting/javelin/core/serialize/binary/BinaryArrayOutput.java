package cn.donting.javelin.core.serialize.binary;

import cn.donting.javelin.core.assets.Asset;
import cn.donting.javelin.core.serialize.Serializable;

import java.lang.reflect.Array;

/**
 * 数组序列化输出实现
 *
 * <p>模式由数组元素类型决定：</p>
 * <ul>
 *   <li>OBJECT_REFS：元素为 Serializable，写对象 id 引用</li>
 *   <li>ASSET_REFS：元素为 Asset，写 GUID 引用</li>
 *   <li>DIRECT_VALUES：基本类型 / String / Enum，内联写值</li>
 * </ul>
 *
 * @author javelin
 * @since 1.0
 */
class BinaryArrayOutput implements BinaryOutput {

    static final byte MODE_OBJECT_REFS = 1;
    static final byte MODE_DIRECT_VALUES = 2;
    static final byte MODE_ASSET_REFS = 3;

    private final Object array;
    private final BinaryExporter exporter;
    private final int id;
    private int[] objectIds;
    private String[] assetGuids;
    private byte mode;

    public BinaryArrayOutput(int id, Object array, BinaryExporter exporter) {
        this.id = id;
        this.array = array;
        this.exporter = exporter;
    }

    @Override
    public void collect() {
        Class<?> elementClass = array.getClass().getComponentType();
        if (Asset.class.isAssignableFrom(elementClass)) {
            Asset[] assets = (Asset[]) array;
            assetGuids = new String[assets.length];
            mode = MODE_ASSET_REFS;
            for (int i = 0; i < assets.length; i++) {
                assetGuids[i] = assets[i] != null ? assets[i].getGuid() : null;
            }
        } else if (Serializable.class.isAssignableFrom(elementClass)) {
            Serializable[] serializables = (Serializable[]) array;
            objectIds = new int[serializables.length];
            mode = MODE_OBJECT_REFS;
            for (int i = 0; i < serializables.length; i++) {
                BinaryOutputCapsule output = exporter.getOutput(serializables[i]);
                objectIds[i] = output != null ? output.getId() : BinaryExporter.NULL_OBJECT_ID;
            }
        } else {
            mode = MODE_DIRECT_VALUES;
        }
    }

    @Override
    public void writeTo(BinaryStreamOutput out) {
        out.write(mode);
        if (mode == MODE_OBJECT_REFS) {
            for (int objectId : objectIds) {
                out.write(objectId);
            }
        } else if (mode == MODE_ASSET_REFS) {
            for (String guid : assetGuids) {
                out.write(guid);
            }
        } else {
            writeDirectValues(out);
        }
    }

    private void writeDirectValues(BinaryStreamOutput out) {
        Class<?> componentType = array.getClass().getComponentType();
        if (componentType == byte.class) {
            byte[] arr = (byte[]) array;
            for (byte v : arr) {
                out.write(v);
            }
        } else if (componentType == short.class) {
            short[] arr = (short[]) array;
            for (short v : arr) {
                out.write(v);
            }
        } else if (componentType == int.class) {
            int[] arr = (int[]) array;
            for (int v : arr) {
                out.write(v);
            }
        } else if (componentType == long.class) {
            long[] arr = (long[]) array;
            for (long v : arr) {
                out.write(v);
            }
        } else if (componentType == float.class) {
            float[] arr = (float[]) array;
            for (float v : arr) {
                out.write(v);
            }
        } else if (componentType == double.class) {
            double[] arr = (double[]) array;
            for (double v : arr) {
                out.write(v);
            }
        } else if (componentType == boolean.class) {
            boolean[] arr = (boolean[]) array;
            for (boolean v : arr) {
                out.write(v);
            }
        } else if (componentType == char.class) {
            char[] arr = (char[]) array;
            for (char v : arr) {
                out.write(v);
            }
        } else if (componentType == String.class) {
            String[] arr = (String[]) array;
            for (String v : arr) {
                out.write(v);
            }
        } else if (Enum.class.isAssignableFrom(componentType)) {
            Enum[] arr = (Enum[]) array;
            for (Enum v : arr) {
                out.write(v != null ? v.name() : null);
            }
        } else {
            throw new IllegalArgumentException("不支持的数组元素类型: " + componentType);
        }
    }

    @Override
    public Object getValue() {
        return array;
    }

    @Override
    public int getId() {
        return id;
    }

    public Class<?> getElementClass() {
        return array.getClass().getComponentType();
    }

    public int getLength() {
        return Array.getLength(array);
    }
}
