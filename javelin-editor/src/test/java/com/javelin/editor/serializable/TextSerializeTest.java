package com.javelin.editor.serializable;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.core.serialize.Serializable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TextExporter / TextImporter 序列化测试。
 * <p>覆盖：基本类型、循环引用、共享引用、数组、ArrayList、JOML 类型、Enum</p>
 */
public class TextSerializeTest {

    @TempDir
    File tempDir;

    private File getFile(String name) {
        return new File(tempDir, name);
    }

    // region 基础类型测试

    @Test
    void testBasicTypes() throws Exception {
        File file = getFile("basic.txt");
        BasicData original = new BasicData();
        original.byteVal = 1;
        original.shortVal = 2;
        original.intVal = 3;
        original.longVal = 4L;
        original.floatVal = 5.5f;
        original.doubleVal = 6.6;
        original.boolVal = true;
        original.charVal = 'A';
        original.str = "hello";

        new TextExporter(file).export(original);

        BasicData restored = new TextImporter(file).importData(BasicData.class);
        assertEquals(original.byteVal, restored.byteVal);
        assertEquals(original.shortVal, restored.shortVal);
        assertEquals(original.intVal, restored.intVal);
        assertEquals(original.longVal, restored.longVal);
        assertEquals(original.floatVal, restored.floatVal, 0.0001);
        assertEquals(original.doubleVal, restored.doubleVal, 0.0001);
        assertEquals(original.boolVal, restored.boolVal);
        assertEquals(original.charVal, restored.charVal);
        assertEquals(original.str, restored.str);
    }

    @Test
    void testNullValues() throws Exception {
        File file = getFile("null.txt");
        BasicData original = new BasicData();
        original.str = null;

        new TextExporter(file).export(original);

        BasicData restored = new TextImporter(file).importData(BasicData.class);
        assertNull(restored.str);
    }

    // endregion

    // region 循环引用测试

    @Test
    void testCircularReference() throws Exception {
        File file = getFile("circular.txt");
        Node a = new Node("A");
        Node b = new Node("B");
        a.friend = b;
        b.friend = a;

        new TextExporter(file).export(a);

        Node restored = new TextImporter(file).importData(Node.class);
        assertEquals("A", restored.name);
        assertEquals("B", restored.friend.name);
        assertSame(restored, restored.friend.friend);  // A -> B -> A
    }

    @Test
    void testSelfReference() throws Exception {
        File file = getFile("self.txt");
        Node a = new Node("A");
        a.friend = a;

        new TextExporter(file).export(a);

        Node restored = new TextImporter(file).importData(Node.class);
        assertSame(restored, restored.friend);  // A -> A
    }

    // endregion

    // region 共享引用测试

    @Test
    void testSharedReference() throws Exception {
        File file = getFile("shared.txt");
        Node shared = new Node("Shared");
        Container c = new Container();
        c.first = shared;
        c.second = shared;

        new TextExporter(file).export(c);

        Container restored = new TextImporter(file).importData(Container.class);
        assertSame(restored.first, restored.second);
        assertEquals("Shared", restored.first.name);
    }

    // endregion

    // region 数组测试

    @Test
    void testPrimitiveArray() throws Exception {
        File file = getFile("array.txt");
        ArrayHolder original = new ArrayHolder();
        original.ints = new int[]{1, 2, 3};
        original.strings = new String[]{"a", "b", "c"};

        new TextExporter(file).export(original);

        ArrayHolder restored = new TextImporter(file).importData(ArrayHolder.class);
        assertArrayEquals(new int[]{1, 2, 3}, restored.ints);
        assertArrayEquals(new String[]{"a", "b", "c"}, restored.strings);
    }

    @Test
    void testSerializableArray() throws Exception {
        File file = getFile("serializable_array.txt");
        NodeArrayHolder original = new NodeArrayHolder();
        original.nodes = new Node[]{new Node("A"), new Node("B")};

        new TextExporter(file).export(original);

        NodeArrayHolder restored = new TextImporter(file).importData(NodeArrayHolder.class);
        assertEquals(2, restored.nodes.length);
        assertEquals("A", restored.nodes[0].name);
        assertEquals("B", restored.nodes[1].name);
    }

    // endregion

    // region ArrayList 测试

    @Test
    void testArrayList() throws Exception {
        File file = getFile("arraylist.txt");
        ListHolder original = new ListHolder();
        original.strings = new ArrayList<>(List.of("x", "y", "z"));

        new TextExporter(file).export(original);

        ListHolder restored = new TextImporter(file).importData(ListHolder.class);
        assertEquals(3, restored.strings.size());
        assertEquals("x", restored.strings.get(0));
        assertEquals("y", restored.strings.get(1));
        assertEquals("z", restored.strings.get(2));
    }

    @Test
    void testEnumList() throws Exception {
        File file = getFile("enumlist.txt");
        EnumListHolder original = new EnumListHolder();
        original.statuses = new ArrayList<>(List.of(Status.ACTIVE, Status.INACTIVE, Status.ACTIVE));

        new TextExporter(file).export(original);

        EnumListHolder restored = new TextImporter(file).importData(EnumListHolder.class);
        assertEquals(3, restored.statuses.size());
        assertEquals(Status.ACTIVE, restored.statuses.get(0));
        assertEquals(Status.INACTIVE, restored.statuses.get(1));
        assertEquals(Status.ACTIVE, restored.statuses.get(2));
    }

    // endregion

    // region JOML 类型测试

    @Test
    void testJomlTypes() throws Exception {
        File file = getFile("joml.txt");
        JomlData original = new JomlData();
        original.position = new org.joml.Vector3f(1, 2, 3);
        original.rotation = new org.joml.Quaternionf(0, 0, 0, 1);
        original.matrix = new org.joml.Matrix4f().identity();

        new TextExporter(file).export(original);

        JomlData restored = new TextImporter(file).importData(JomlData.class);
        assertEquals(1f, restored.position.x, 0.0001f);
        assertEquals(2f, restored.position.y, 0.0001f);
        assertEquals(3f, restored.position.z, 0.0001f);
        assertEquals(0f, restored.rotation.x, 0.0001f);
        assertEquals(0f, restored.rotation.y, 0.0001f);
        assertEquals(0f, restored.rotation.z, 0.0001f);
        assertEquals(1f, restored.rotation.w, 0.0001f);
    }

    // endregion

    // region 嵌套循环引用（复杂场景）

    @Test
    void testNestedCircularReference() throws Exception {
        File file = getFile("nested_circular.txt");
        // A -> B -> C -> A
        Node a = new Node("A");
        Node b = new Node("B");
        Node c = new Node("C");
        a.friend = b;
        b.friend = c;
        c.friend = a;

        new TextExporter(file).export(a);

        Node restored = new TextImporter(file).importData(Node.class);
        assertEquals("A", restored.name);
        assertEquals("B", restored.friend.name);
        assertEquals("C", restored.friend.friend.name);
        assertSame(restored, restored.friend.friend.friend);  // A -> B -> C -> A
    }

    // endregion

    // region 测试数据类

    /**
     * 基础数据类
     */
    @Reflective
    public static class BasicData implements Serializable {
        byte byteVal;
        short shortVal;
        int intVal;
        long longVal;
        float floatVal;
        double doubleVal;
        boolean boolVal;
        char charVal;
        String str;

        @Override
        public void write(OutputCapsule output) {
            output.write("byteVal", byteVal);
            output.write("shortVal", shortVal);
            output.write("intVal", intVal);
            output.write("longVal", longVal);
            output.write("floatVal", floatVal);
            output.write("doubleVal", doubleVal);
            output.write("boolVal", boolVal);
            output.write("charVal", charVal);
            output.write("str", str);
        }

        @Override
        public void read(InputCapsule input) {
            byteVal = input.readByte("byteVal");
            shortVal = input.readShort("shortVal");
            intVal = input.readInt("intVal");
            longVal = input.readLong("longVal");
            floatVal = input.readFloat("floatVal");
            doubleVal = input.readDouble("doubleVal");
            boolVal = input.readBoolean("boolVal");
            charVal = input.readChar("charVal");
            str = input.readString("str");
        }
    }

    /**
     * 节点类（支持循环引用）
     */
    @Reflective
    public static class Node implements Serializable {
        String name;
        Node friend;

        public Node() {}

        public Node(String name) {
            this.name = name;
        }

        @Override
        public void write(OutputCapsule output) {
            output.write("name", name);
            output.write("friend", friend);
        }

        @Override
        public void read(InputCapsule input) {
            name = input.readString("name");
            friend = input.readSerializable("friend", Node.class);
        }
    }

    /**
     * 容器类（测试共享引用）
     */
    @Reflective
    public static class Container implements Serializable {
        Node first;
        Node second;

        @Override
        public void write(OutputCapsule output) {
            output.write("first", first);
            output.write("second", second);
        }

        @Override
        public void read(InputCapsule input) {
            first = input.readSerializable("first", Node.class);
            second = input.readSerializable("second", Node.class);
        }
    }

    /**
     * 数组持有者
     */
    @Reflective
    public static class ArrayHolder implements Serializable {
        int[] ints;
        String[] strings;

        @Override
        public void write(OutputCapsule output) {
            output.write("ints", ints);
            output.write("strings", strings);
        }

        @Override
        public void read(InputCapsule input) {
            ints = input.readIntArray("ints");
            strings = input.readStringArray("strings");
        }
    }

    /**
     * 节点数组持有者
     */
    @Reflective
    public static class NodeArrayHolder implements Serializable {
        Node[] nodes;

        @Override
        public void write(OutputCapsule output) {
            output.write("nodes", nodes);
        }

        @Override
        public void read(InputCapsule input) {
            nodes = input.readSerializableArray("nodes", Node.class);
        }
    }

    /**
     * 列表持有者
     */
    @Reflective
    public static class ListHolder implements Serializable {
        ArrayList<String> strings;

        @Override
        public void write(OutputCapsule output) {
            output.writeArrayString("strings", strings);
        }

        @Override
        public void read(InputCapsule input) {
            strings = input.readArrayString("strings");
        }
    }

    /**
     * 状态枚举
     */
    @Reflective
    public enum Status {
        ACTIVE, INACTIVE, PENDING
    }

    /**
     * 枚举列表持有者
     */
    @Reflective
    public static class EnumListHolder implements Serializable {
        ArrayList<Status> statuses;

        @Override
        public void write(OutputCapsule output) {
            output.writeEnumList("statuses", statuses);
        }

        @Override
        public void read(InputCapsule input) {
            statuses = input.readEnumList("statuses", Status.class);
        }
    }

    /**
     * JOML 数据类
     */
    @Reflective
    public static class JomlData implements Serializable {
        org.joml.Vector3f position;
        org.joml.Quaternionf rotation;
        org.joml.Matrix4f matrix;

        @Override
        public void write(OutputCapsule output) {
            output.write("position", position);
            output.write("rotation", rotation);
            output.write("matrix", matrix);
        }

        @Override
        public void read(InputCapsule input) {
            position = input.readVector3f("position");
            rotation = input.readQuaternionf("rotation");
            matrix = input.readMatrix4f("matrix");
        }
    }

    // endregion
}
