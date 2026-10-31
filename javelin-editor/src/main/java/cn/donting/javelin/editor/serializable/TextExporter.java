package cn.donting.javelin.editor.serializable;


import cn.donting.javelin.core.assets.Asset;
import cn.donting.javelin.core.serialize.Exporter;
import cn.donting.javelin.core.serialize.Serializable;
import cn.donting.javelin.editor.plugin.EditorLang;

import java.io.*;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class TextExporter implements Exporter {

    public static final String FILE_HEADER = "javelin-text-serialization";
    public static final String VERSION = "1";
    public static final int NULL_OBJECT_ID = -1;

    private final File outputFile;
    private final Map<Object, Integer> objectMap = new IdentityHashMap<>();
    private final List<Object> orderedObjects = new ArrayList<>();
    private final Map<Object, TextOutputCapsule> capsuleMap = new IdentityHashMap<>();
    private int nextId = 0;
    /** 本导出的根对象（Asset 分流用：图内根引用走对象 id 而非 GUID，见 isRoot） */
    private Serializable root;

    public TextExporter(File outputFile) {
        this.outputFile = outputFile;
    }

    @Override
    public void export(Serializable serializable) throws IOException {
        objectMap.clear();
        orderedObjects.clear();
        capsuleMap.clear();
        nextId = 0;
        this.root = serializable;

        getObjectId(serializable);

        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        try (FileOutputStream fos = new FileOutputStream(outputFile);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
             BufferedWriter writer = new BufferedWriter(osw)) {
            writer.write(FILE_HEADER);
            writer.write("\n");
            writer.write("version=" + VERSION);
            writer.write("\n");

            for (Object obj : orderedObjects) {
                int id = objectMap.get(obj);
                if (obj.getClass().isArray()) {
                    writeArray(writer, obj, id);
                } else if (obj instanceof ArrayList) {
                    writeArrayList(writer, (ArrayList<?>) obj, id);
                } else if (obj instanceof Serializable) {
                    TextOutputCapsule capsule = capsuleMap.get(obj);
                    if (capsule == null) {
                        capsule = new TextOutputCapsule((Serializable) obj, this);
                        capsule.write();
                        capsuleMap.put(obj, capsule);
                    }
                    capsule.writeTo(writer, id);
                } else {
                    throw new RuntimeException(EditorLang.text("text.unsupportedType", obj.getClass()));
                }
            }
            // 强制把缓冲区数据刷到文件系统并同步到磁盘，
            // 避免 export 返回后立刻读取时拿到不完整内容
            writer.flush();
            fos.getFD().sync();
        }
    }

    int getObjectId(Object object) {
        if (object == null) {
            return NULL_OBJECT_ID;
        }
        Integer id = objectMap.get(object);
        if (id != null) {
            return id;
        }

        id = nextId++;
        objectMap.put(object, id);
        orderedObjects.add(object);

        if (object.getClass().isArray()) {
            preRegisterArrayElements(object);
        } else if (object instanceof ArrayList<?> list) {
            preRegisterArrayListElements(list);
        } else if (object instanceof Serializable s) {
            TextOutputCapsule capsule = new TextOutputCapsule(s, this);
            capsuleMap.put(object, capsule);
            capsule.write();
        } else {
            throw new RuntimeException(EditorLang.text("text.unsupportedType", object.getClass()));
        }

        return id;
    }

    /**
     * 引用值是否为本导出的根对象（实例同一性）。
     *
     * <p>供 Capsule 的 Asset 分流判定：根对象被图内字段引用时（如 Node.scene →
     * GameScene）走 @ref 对象 id 而非 @guid——图内根引用语义 vs 外部资产 GUID 语义
     *（2026-10-02 拍板，与 BinaryExporter 同款口径）。读侧按落盘标记分流，零改动。</p>
     */
    boolean isRoot(Object value) {
        return value == root;
    }

    private void preRegisterArrayElements(Object array) {
        Class<?> elementClass = array.getClass().getComponentType();
        if (Serializable.class.isAssignableFrom(elementClass) && !Asset.class.isAssignableFrom(elementClass)) {
            int length = Array.getLength(array);
            for (int i = 0; i < length; i++) {
                Object element = Array.get(array, i);
                if (element != null) {
                    getObjectId(element);
                }
            }
        }
    }

    private void preRegisterArrayListElements(ArrayList<?> list) {
        for (int i = 0; i < list.size(); i++) {
            Object element = list.get(i);
            if (element instanceof Serializable && !(element instanceof Asset)) {
                getObjectId(element);
            }
        }
    }

    private void writeArray(Appendable out, Object array, int id) throws IOException {
        Class<?> componentType = array.getClass().getComponentType();
        out.append("[").append(String.valueOf(id)).append(":").append(componentType.getName()).append("[]]\n");

        int length = Array.getLength(array);
        out.append("[");
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                out.append(",");
            }
            Object element = Array.get(array, i);
            out.append(serializeArrayElement(element, componentType));
        }
        out.append("]\n");
    }

    private void writeArrayList(Appendable out, ArrayList<?> list, int id) throws IOException {
        String elementClassName = getArrayListElementClassName(list);
        out.append("[").append(String.valueOf(id)).append(":").append("java.util.ArrayList");
        if (elementClassName != null) {
            out.append("<").append(elementClassName).append(">");
        }
        out.append("]\n");
        out.append("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                out.append(",");
            }
            Object element = list.get(i);
            out.append(serializeArrayListElement(element));
        }
        out.append("]\n");
    }

    private String getArrayListElementClassName(ArrayList<?> list) {
        for (Object element : list) {
            if (element == null) {
                continue;
            }
            // Asset 写具体类名（首个非 null 元素的运行时类，与 BinaryArrayListOutput 同口径）：
            // 读侧 @guid 分支经此类型实例化，写父类 Asset 会「未注册的类」崩
            return element.getClass().getName();
        }
        return null;
    }

    String serializeArrayElement(Object element, Class<?> elementClass) {
        if (element == null) {
            return "@null";
        }
        if (Asset.class.isAssignableFrom(elementClass)) {
            return "@guid:" + ((Asset) element).getGuid();
        }
        if (Serializable.class.isAssignableFrom(elementClass)) {
            return "@ref:" + getObjectId(element);
        }
        if (elementClass.isEnum()) {
            return ((Enum<?>) element).name();
        }
        if (elementClass == String.class) {
            return escape((String) element);
        }
        return String.valueOf(element);
    }

    String serializeArrayListElement(Object element) {
        if (element == null) {
            return "@null";
        }
        if (element instanceof Asset asset) {
            return "@guid:" + asset.getGuid();
        }
        if (element instanceof Serializable s) {
            return "@ref:" + getObjectId(s);
        }
        if (element instanceof Enum<?> e) {
            return e.name();
        }
        if (element instanceof String str) {
            return escape(str);
        }
        return String.valueOf(element);
    }

    static String escape(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '=' -> sb.append("\\=");
                case ',' -> sb.append("\\,");
                case '[' -> sb.append("\\[");
                case ']' -> sb.append("\\]");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }


}
