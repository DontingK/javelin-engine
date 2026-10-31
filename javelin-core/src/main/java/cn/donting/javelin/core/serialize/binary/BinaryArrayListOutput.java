package cn.donting.javelin.core.serialize.binary;

import cn.donting.javelin.core.assets.Asset;
import cn.donting.javelin.core.serialize.Serializable;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * ArrayList 序列化输出实现
 *
 * <p>元素类型由首个非 null 元素决定，写出前校验全部非 null 元素类型一致，
 * 不一致抛异常（杜绝静默丢数据）。支持四种模式：</p>
 * <ul>
 *   <li>OBJECT_REFS：元素为 Serializable，写对象 id 引用</li>
 *   <li>STRING_VALUES：元素为 String，内联写值</li>
 *   <li>ASSET_REFS：元素为 Asset，写 GUID 引用</li>
 *   <li>ENUM_VALUES：元素为 Enum，写 name()，元素类名记录在对象头区</li>
 * </ul>
 *
 * @author javelin
 * @since 1.0
 */
class BinaryArrayListOutput implements BinaryOutput {

    static final byte MODE_OBJECT_REFS = 1;
    static final byte MODE_STRING_VALUES = 2;
    static final byte MODE_ASSET_REFS = 3;
    static final byte MODE_ENUM_VALUES = 4;

    private final ArrayList<?> list;
    private final BinaryExporter exporter;
    private final int id;
    private int[] objectIds;
    private String[] stringValues;
    private byte mode;
    /** 首个非 null 元素的类全名（全 null 列表为 null），写入对象头区供导入端重建元素类型 */
    private String elementClassName;

    public BinaryArrayListOutput(int id, ArrayList<?> list, BinaryExporter exporter) {
        this.id = id;
        this.list = list;
        this.exporter = exporter;
    }

    @Override
    public void collect() {
        Object first = null;
        for (Object e : list) {
            if (e != null) {
                first = e;
                break;
            }
        }
        if (first == null) {
            // 全 null 列表：按对象引用模式写，全部为 null 引用，可无损往返
            mode = MODE_OBJECT_REFS;
            objectIds = new int[list.size()];
            Arrays.fill(objectIds, BinaryExporter.NULL_OBJECT_ID);
            return;
        }

        Class<?> elementClass = first.getClass();
        elementClassName = elementClass.getName();

        if (first instanceof Asset) {
            mode = MODE_ASSET_REFS;
            stringValues = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object e = list.get(i);
                if (e == null) {
                    stringValues[i] = null;
                } else if (e instanceof Asset asset) {
                    stringValues[i] = asset.getGuid();
                } else {
                    throw new IllegalStateException(
                        "ArrayList 元素类型不一致: 首元素为 " + elementClass.getName()
                            + "，存在非 Asset 元素 " + e.getClass().getName());
                }
            }
        } else if (first instanceof Serializable) {
            mode = MODE_OBJECT_REFS;
            objectIds = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object e = list.get(i);
                if (e == null) {
                    objectIds[i] = BinaryExporter.NULL_OBJECT_ID;
                } else if (e instanceof Serializable s) {
                    BinaryOutputCapsule output = exporter.getOutput(s);
                    objectIds[i] = output != null ? output.getId() : BinaryExporter.NULL_OBJECT_ID;
                } else {
                    throw new IllegalStateException(
                        "ArrayList 元素类型不一致: 首元素为 " + elementClass.getName()
                            + "，存在非 Serializable 元素 " + e.getClass().getName());
                }
            }
        } else if (first instanceof String) {
            mode = MODE_STRING_VALUES;
            stringValues = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object e = list.get(i);
                if (e == null) {
                    stringValues[i] = null;
                } else if (e instanceof String s) {
                    stringValues[i] = s;
                } else {
                    throw new IllegalStateException(
                        "ArrayList 元素类型不一致: 首元素为 String，存在 "
                            + e.getClass().getName());
                }
            }
        } else if (first instanceof Enum) {
            mode = MODE_ENUM_VALUES;
            stringValues = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object e = list.get(i);
                if (e == null) {
                    stringValues[i] = null;
                } else if (e.getClass() == elementClass) {
                    stringValues[i] = ((Enum<?>) e).name();
                } else {
                    throw new IllegalStateException(
                        "ArrayList 元素类型不一致: 首元素为 " + elementClass.getName()
                            + "，存在 " + e.getClass().getName());
                }
            }
        } else {
            throw new IllegalArgumentException("不支持的 ArrayList 元素类型: " + elementClassName);
        }
    }

    @Override
    public void writeTo(BinaryStreamOutput out) {
        out.write(mode);
        out.write(list.size());
        if (mode == MODE_OBJECT_REFS) {
            for (int objectId : objectIds) {
                out.write(objectId);
            }
        } else {
            // STRING_VALUES / ASSET_REFS / ENUM_VALUES：均为逐元素字符串
            for (String value : stringValues) {
                out.write(value);
            }
        }
    }

    @Override
    public Object getValue() {
        return list;
    }

    @Override
    public int getId() {
        return id;
    }

    public String getElementClassName() {
        return elementClassName;
    }

    public int getSize() {
        return list.size();
    }
}
