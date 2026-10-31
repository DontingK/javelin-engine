package cn.donting.javelin.core.serialize.binary;

import cn.donting.javelin.core.assets.Asset;
import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.reflective.ReflectiveFactory;
import cn.donting.javelin.core.serialize.Importer;
import cn.donting.javelin.core.serialize.Serializable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 二进制导入器
 *
 * <p>从二进制文件导入 Serializable 对象，支持循环引用解析</p>
 *
 * @author javelin
 * @since 1.0
 */
public class BinaryImporter implements Importer {

    public static final int NULL_OBJECT_ID = -1;
    private static final byte TYPE_SERIALIZABLE = 1;
    private static final byte TYPE_ARRAY = 2;
    private static final byte TYPE_ARRAY_LIST = 3;

    private final File inputFile;
    private final Map<Integer, Object> objectCache = new HashMap<>();
    /** ArrayList 对象 id → 元素类全名（首个非 null 元素的类，供 Enum/Asset 模式重建元素类型） */
    private final Map<Integer, String> arrayListElementClasses = new HashMap<>();
    private BinaryStreamInput binaryStreamInput;
    private AssetManager assetManager;

    public BinaryImporter(File inputFile) {
        this.inputFile = inputFile;
    }
    public BinaryImporter(File inputFile,AssetManager assetManager) {
        this.inputFile = inputFile;
        this.assetManager = assetManager;
    }

    public AssetManager getAssetManager() {
        return assetManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Serializable> T importData(Class<T> tCall) throws Exception {
        try {
            // 重置状态，支持同一实例多次导入
            objectCache.clear();
            arrayListElementClasses.clear();

            binaryStreamInput = new BinaryStreamInput(new FileInputStream(inputFile));


            readHead(binaryStreamInput);

            int objectCount = binaryStreamInput.readInt();

            // 读取对象头，创建实例
            for (int i = 0; i < objectCount; i++) {
                int id = binaryStreamInput.readInt();
                byte objectType = binaryStreamInput.readByte();

                if (objectType == TYPE_ARRAY) {
                    String elementClassName = binaryStreamInput.readString();
                    int length = binaryStreamInput.readInt();
                    Object array = createArray(elementClassName, length);
                    objectCache.put(id, array);
                } else if (objectType == TYPE_ARRAY_LIST) {
                    String elementClassName = binaryStreamInput.readString();
                    int size = binaryStreamInput.readInt();
                    ArrayList<Object> list = new ArrayList<>(size);
                    objectCache.put(id, list);
                    arrayListElementClasses.put(id, elementClassName);
                } else {
                    String className = binaryStreamInput.readString();
                    // 根对象（id=0）按调用方传入的类型实例化，支持字段演进的跨类迁移；
                    // 嵌套对象仍按文件记录的类名实例化
                    Class<?> targetClass = (id == 0 && tCall != null)
                        ? tCall
                        : ReflectiveFactory.getRegisteredClass(className);
                    Serializable instance = (Serializable) ReflectiveFactory.newInstance(targetClass);
                    objectCache.put(id, instance);
                }
            }

            // 读取对象数据（先全部读入，再统一 populate，避免前向引用未填充）
            List<BinaryInputCapsule> capsules = new ArrayList<>();
            for (int i = 0; i < objectCount; i++) {
                int id = binaryStreamInput.readInt();
                Object object = objectCache.get(id);

                if (object.getClass().isArray()) {
                    readArrayData(object);
                } else if (object instanceof ArrayList) {
                    readArrayListData(id, (ArrayList<?>) object);
                } else {
                    BinaryInputCapsule capsule = new BinaryInputCapsule(id, (Serializable) object, this);
                    capsule.read(binaryStreamInput);
                    capsules.add(capsule);
                }
            }

            // 统一填充对象字段（此时所有引用的数组/列表数据已就绪）
            for (BinaryInputCapsule capsule : capsules) {
                capsule.populate();
            }

            return (T) objectCache.get(0);
        } finally {
            if (binaryStreamInput != null) {
                binaryStreamInput.close();
                binaryStreamInput = null;
            }
        }
    }

    Object getObject(int id) {
        return id == NULL_OBJECT_ID ? null : objectCache.get(id);
    }

    /**
     * 解析 Asset 引用（GUID → 资产实例）。
     *
     * @throws IllegalStateException 如果未提供 AssetManager
     */
    Asset loadAsset(String guid, Class<? extends Asset> type) {
        if (assetManager == null) {
            throw new IllegalStateException(
                "反序列化包含 Asset 引用的数据需要 AssetManager，请使用 BinaryImporter(File, AssetManager) 构造");
        }
        return assetManager.loadGuid(guid, type);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void readArrayListData(int id, ArrayList list) {
        byte mode = binaryStreamInput.readByte();
        int size = binaryStreamInput.readInt();
        switch (mode) {
            case BinaryArrayListOutput.MODE_OBJECT_REFS -> {
                for (int i = 0; i < size; i++) {
                    int objId = binaryStreamInput.readInt();
                    list.add(objId != NULL_OBJECT_ID ? (Serializable) getObject(objId) : null);
                }
            }
            case BinaryArrayListOutput.MODE_ASSET_REFS -> {
                Class<? extends Asset> elementClass = getListElementClass(id).asSubclass(Asset.class);
                for (int i = 0; i < size; i++) {
                    String guid = binaryStreamInput.readString();
                    list.add(guid != null ? loadAsset(guid, elementClass) : null);
                }
            }
            case BinaryArrayListOutput.MODE_ENUM_VALUES -> {
                Class<Enum> elementClass = (Class<Enum>) getListElementClass(id);
                for (int i = 0; i < size; i++) {
                    String name = binaryStreamInput.readString();
                    list.add(name != null ? Enum.valueOf(elementClass, name) : null);
                }
            }
            default -> {
                for (int i = 0; i < size; i++) {
                    list.add(binaryStreamInput.readString());
                }
            }
        }
    }

    /**
     * 获取 ArrayList 对象的元素类（首个非 null 元素的类）。
     */
    private Class<?> getListElementClass(int id) {
        String className = arrayListElementClasses.get(id);
        if (className == null) {
            throw new IllegalStateException("ArrayList 缺少元素类型信息（对象 id=" + id + "），文件可能损坏");
        }
        return ReflectiveFactory.getRegisteredClass(className);
    }

    private void readArrayData(Object array) {
        byte mode = binaryStreamInput.readByte();
        Class<?> componentType = array.getClass().getComponentType();

        if (mode == BinaryArrayOutput.MODE_OBJECT_REFS && Serializable.class.isAssignableFrom(componentType)) {
            Serializable[] arr = (Serializable[]) array;
            for (int i = 0; i < arr.length; i++) {
                int objId = binaryStreamInput.readInt();
                arr[i] = objId != NULL_OBJECT_ID ? (Serializable) getObject(objId) : null;
            }
        } else if (mode == BinaryArrayOutput.MODE_ASSET_REFS && Asset.class.isAssignableFrom(componentType)) {
            Asset[] arr = (Asset[]) array;
            Class<? extends Asset> assetClass = componentType.asSubclass(Asset.class);
            for (int i = 0; i < arr.length; i++) {
                String guid = binaryStreamInput.readString();
                arr[i] = guid != null ? loadAsset(guid, assetClass) : null;
            }
        } else {
            readDirectArrayValues(array, componentType);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void readDirectArrayValues(Object array, Class<?> componentType) {
        if (componentType == byte.class) {
            byte[] arr = (byte[]) array;
            for (int i = 0; i < arr.length; i++) {
                arr[i] = binaryStreamInput.readByte();
            }
        } else if (componentType == short.class) {
            short[] arr = (short[]) array;
            for (int i = 0; i < arr.length; i++) {
                arr[i] = binaryStreamInput.readShort();
            }
        } else if (componentType == int.class) {
            int[] arr = (int[]) array;
            for (int i = 0; i < arr.length; i++) {
                arr[i] = binaryStreamInput.readInt();
            }
        } else if (componentType == long.class) {
            long[] arr = (long[]) array;
            for (int i = 0; i < arr.length; i++) {
                arr[i] = binaryStreamInput.readLong();
            }
        } else if (componentType == float.class) {
            float[] arr = (float[]) array;
            for (int i = 0; i < arr.length; i++) {
                arr[i] = binaryStreamInput.readFloat();
            }
        } else if (componentType == double.class) {
            double[] arr = (double[]) array;
            for (int i = 0; i < arr.length; i++) {
                arr[i] = binaryStreamInput.readDouble();
            }
        } else if (componentType == boolean.class) {
            boolean[] arr = (boolean[]) array;
            for (int i = 0; i < arr.length; i++) {
                arr[i] = binaryStreamInput.readBoolean();
            }
        } else if (componentType == char.class) {
            char[] arr = (char[]) array;
            for (int i = 0; i < arr.length; i++) {
                arr[i] = binaryStreamInput.readChar();
            }
        } else if (componentType == String.class) {
            String[] arr = (String[]) array;
            for (int i = 0; i < arr.length; i++) {
                arr[i] = binaryStreamInput.readString();
            }
        } else if (Enum.class.isAssignableFrom(componentType)) {
            Enum[] arr = (Enum[]) array;
            for (int i = 0; i < arr.length; i++) {
                String name = binaryStreamInput.readString();
                arr[i] = name != null ? Enum.valueOf((Class<Enum>) componentType, name) : null;
            }
        }
    }

    private static Object createArray(String elementClassName, int length) {
        Object primitiveArray = createPrimitiveArray(elementClassName, length);
        if (primitiveArray != null) {
            return primitiveArray;
        }
        return ReflectiveFactory.newArray(elementClassName, length);
    }

    private static Object createPrimitiveArray(String elementClassName, int length) {
        return switch (elementClassName) {
            case "byte" -> new byte[length];
            case "short" -> new short[length];
            case "int" -> new int[length];
            case "long" -> new long[length];
            case "float" -> new float[length];
            case "double" -> new double[length];
            case "boolean" -> new boolean[length];
            case "char" -> new char[length];
            case "java.lang.String" -> new String[length];
            default -> null;
        };
    }

    protected void readHead(BinaryStreamInput input) throws IOException {
        int fileFlag = input.readInt();
        if (fileFlag != BinaryExporter.FILE_FLAG_SERIALIZABLE) {
            throw new IOException("文件格式错误，不是 Serializable 对象");
        }
    }
}
