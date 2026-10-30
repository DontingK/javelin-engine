package com.javelin.editor.serializable;


import com.javelin.core.assets.Asset;
import com.javelin.core.assets.AssetManager;
import com.javelin.core.reflective.ReflectiveFactory;
import com.javelin.core.serialize.Importer;
import com.javelin.core.serialize.Serializable;
import com.javelin.editor.plugin.EditorLang;

import java.io.*;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class TextImporter implements Importer {

    public static final int NULL_OBJECT_ID = -1;

    private final File inputFile;
    private final AssetManager assetManager;
    private final Map<Integer, Object> objectCache = new HashMap<>();
    private final Map<Integer, String> classNames = new HashMap<>();
    private final Map<Integer, Map<String, String>> rawData = new HashMap<>();
    private final Map<Integer, String> arrayData = new HashMap<>();
    private final Set<Integer> arrayIds = new HashSet<>();
    private final Set<Integer> arrayListIds = new HashSet<>();
    private final Map<Integer, String> arrayListElementClasses = new HashMap<>();

    public TextImporter(File inputFile) {
        this(inputFile, null);
    }

    public TextImporter(File inputFile, AssetManager assetManager) {
        this.inputFile = inputFile;
        this.assetManager = assetManager;
    }

    @Override
   public  <T extends Serializable> T importData(Class<T> tCall) throws Exception{
        objectCache.clear();
        classNames.clear();
        rawData.clear();
        arrayData.clear();
        arrayIds.clear();
        arrayListIds.clear();
        arrayListElementClasses.clear();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(inputFile), StandardCharsets.UTF_8))) {
            parseFile(reader);
        }

        for (Map.Entry<Integer, String> entry : classNames.entrySet()) {
            int id = entry.getKey();
            String className = entry.getValue();
            if (arrayIds.contains(id)) {
                String content = arrayData.get(id);
                int length = splitArray(content).length;
                Object primitiveArray = createPrimitiveArray(className, length);
                Object array;
                if (primitiveArray != null) {
                    array = primitiveArray;
                } else {
                    array = ReflectiveFactory.newArray(className, length);
                }
                objectCache.put(id, array);
            } else if (arrayListIds.contains(id)) {
                objectCache.put(id, new ArrayList<>());
            } else {
                Serializable s = ReflectiveFactory.newInstance(className);
                objectCache.put(id, s);
            }
        }

        for (Map.Entry<Integer, String> entry : arrayData.entrySet()) {
            int id = entry.getKey();
            Object obj = objectCache.get(id);
            String content = entry.getValue();
            String[] parts = splitArray(content);
            if (obj.getClass().isArray()) {
                Class<?> elementClass = obj.getClass().getComponentType();
                for (int i = 0; i < parts.length; i++) {
                    setArrayElement(obj, i, parts[i], elementClass);
                }
            } else if (obj instanceof ArrayList) {
                ArrayList list = (ArrayList) obj;
                Class<?> elementClass = getArrayListElementClass(id);
                for (String part : parts) {
                    if (part.equals("@null")) {
                        list.add(null);
                    } else if (part.startsWith("@ref:")) {
                        int refId = Integer.parseInt(part.substring(5));
                        list.add(objectCache.get(refId));
                    } else if (part.startsWith("@guid:")) {
                        String guid = part.substring(6);
                        // 经对象头泛型类实例化（写侧落具体类名）——硬编码 Asset.class 会
                        // 「未注册的类」崩（Asset 基类不在 ReflectiveFactory 注册表，
                        // 同 TextInputCapsule.readSerializable 2026-10-04 坑）
                        if (assetManager == null || elementClass == null) {
                            throw new IllegalStateException(
                                EditorLang.text("text.listGuidUnresolvable", guid));
                        }
                        list.add(assetManager.loadGuid(guid, (Class<? extends Asset>) elementClass));
                    } else if (elementClass != null && elementClass.isEnum()) {
                        list.add(Enum.valueOf((Class<? extends Enum>) elementClass, part));
                    } else {
                        list.add(unescape(part));
                    }
                }
            }
        }

        for (Map.Entry<Integer, Map<String, String>> entry : rawData.entrySet()) {
            int id = entry.getKey();
            Serializable s = (Serializable) objectCache.get(id);
            TextInputCapsule capsule = new TextInputCapsule(s, this, entry.getValue());
            s.read(capsule);
        }

        Object value = objectCache.get(0);
        if (value == null) {
            throw new IOException(EditorLang.text("text.missingRoot"));
        }
        return (T) value;
    }

    public String getGuid() {
        throw new UnsupportedOperationException(EditorLang.text("text.guidDirectReadUnsupported"));
    }

    Object getObject(int id) {
        if (id == NULL_OBJECT_ID) {
            return null;
        }
        return objectCache.get(id);
    }

    AssetManager getAssetManager() {
        return assetManager;
    }

    private Class<?> getArrayListElementClass(int id) {
        String className = arrayListElementClasses.get(id);
        if (className == null) {
            return null;
        }
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private void parseFile(BufferedReader reader) throws IOException {
        String line;
        line = reader.readLine();
        if (line == null || !line.equals(TextExporter.FILE_HEADER)) {
            throw new IOException(EditorLang.text("text.badHeader"));
        }
        line = reader.readLine();

        Integer currentId = null;
        Map<String, String> currentEntries = null;

        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[")) {
                if (currentId != null) {
                    rawData.put(currentId, currentEntries);
                    currentId = null;
                    currentEntries = null;
                }

                int colon = line.indexOf(':');
                int closeBracket = line.lastIndexOf(']');
                if (colon == -1 || closeBracket == -1 || colon > closeBracket) {
                    throw new IOException(EditorLang.text("text.badObjectHeader", line));
                }
                int id = Integer.parseInt(line.substring(1, colon));
                String className = line.substring(colon + 1, closeBracket);

                // 解析 ArrayList 泛型参数: java.util.ArrayList<com.example.MyEnum>
                String arrayListElementClass = null;
                if (className.startsWith("java.util.ArrayList<") && className.endsWith(">")) {
                    arrayListElementClass = className.substring("java.util.ArrayList<".length(), className.length() - 1);
                    className = "java.util.ArrayList";
                }

                boolean isArray = className.endsWith("[]");
                boolean isArrayList = className.equals("java.util.ArrayList");
                if (isArray) {
                    className = className.substring(0, className.length() - 2);
                    arrayIds.add(id);
                } else if (isArrayList) {
                    arrayListIds.add(id);
                    if (arrayListElementClass != null) {
                        arrayListElementClasses.put(id, arrayListElementClass);
                    }
                }
                classNames.put(id, className);

                if (isArray || isArrayList) {
                    String arrayLine = reader.readLine();
                    if (arrayLine == null || !arrayLine.startsWith("[") || !arrayLine.endsWith("]")) {
                        throw new IOException(EditorLang.text("text.badArrayRow", id));
                    }
                    arrayData.put(id, arrayLine.substring(1, arrayLine.length() - 1));
                } else {
                    currentId = id;
                    currentEntries = new LinkedHashMap<>();
                }
            } else if (currentId != null) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key = line.substring(0, eq);
                    String value = line.substring(eq + 1);
                    currentEntries.put(key, value);
                }
            }
        }

        if (currentId != null) {
            rawData.put(currentId, currentEntries);
        }
    }

    private void setArrayElement(Object array, int index, String elementStr, Class<?> elementClass) {
        if (elementStr.equals("@null")) {
            Array.set(array, index, null);
            return;
        }
        if (elementStr.startsWith("@ref:")) {
            int refId = Integer.parseInt(elementStr.substring(5));
            Array.set(array, index, objectCache.get(refId));
            return;
        }
        if (elementStr.startsWith("@guid:")) {
            String guid = elementStr.substring(6);
            if (assetManager != null && Asset.class.isAssignableFrom(elementClass)) {
                Array.set(array, index, assetManager.loadGuid(guid, (Class<? extends Asset>) elementClass));
            } else {
                throw new IllegalStateException(EditorLang.text("text.badGuidArrayElement", guid));
            }
            return;
        }
        if (elementClass.isEnum()) {
            Array.set(array, index, Enum.valueOf((Class<? extends Enum>) elementClass, elementStr));
            return;
        }
        if (elementClass == String.class) {
            Array.set(array, index, unescape(elementStr));
            return;
        }
        if (elementClass == byte.class) {
            Array.setByte(array, index, Byte.parseByte(elementStr));
        } else if (elementClass == short.class) {
            Array.setShort(array, index, Short.parseShort(elementStr));
        } else if (elementClass == int.class) {
            Array.setInt(array, index, Integer.parseInt(elementStr));
        } else if (elementClass == long.class) {
            Array.setLong(array, index, Long.parseLong(elementStr));
        } else if (elementClass == float.class) {
            Array.setFloat(array, index, Float.parseFloat(elementStr));
        } else if (elementClass == double.class) {
            Array.setDouble(array, index, Double.parseDouble(elementStr));
        } else if (elementClass == boolean.class) {
            Array.setBoolean(array, index, Boolean.parseBoolean(elementStr));
        } else if (elementClass == char.class) {
            Array.setChar(array, index, unescape(elementStr).charAt(0));
        } else {
            throw new IllegalArgumentException(EditorLang.text("text.badArrayElementType", elementClass));
        }
    }

    static String[] splitArray(String content) {
        if (content.isEmpty()) {
            return new String[0];
        }
        List<String> parts = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\\' && i + 1 < content.length()) {
                sb.append(c);
                sb.append(content.charAt(++i));
            } else if (c == ',') {
                parts.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        parts.add(sb.toString());
        return parts.toArray(new String[0]);
    }

    static String unescape(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    default -> sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Object createPrimitiveArray(String elementClass, int length) {
        return switch (elementClass) {
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
}
