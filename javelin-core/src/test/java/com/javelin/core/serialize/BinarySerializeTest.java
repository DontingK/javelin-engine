package com.javelin.core.serialize;

import com.javelin.core.assets.Asset;
import com.javelin.core.assets.AssetManager;
import com.javelin.core.reflective.ReflectiveFactory;
import com.javelin.core.serialize.binary.BinaryExporter;
import com.javelin.core.serialize.binary.BinaryImporter;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 序列化系统单元测试：覆盖标量/数组/列表往返、共享与循环引用、
 * 字段演进容忍、Asset GUID 引用、类型校验与实例复用。
 */
class BinarySerializeTest {

    @TempDir
    Path tempDir;

    // ═══════════════════════════════════════════════════════════════════════
    //  测试用类型
    // ═══════════════════════════════════════════════════════════════════════

    public enum Quality {LOW, MEDIUM, HIGH}

    /** 假资产：序列化只写 GUID，不调用 write/read */
    public static class FakeAsset implements Asset {
        final String guid;

        FakeAsset(String guid) {
            this.guid = guid;
        }

        @Override
        public String getGuid() {
            return guid;
        }

        @Override
        public long getLastModified() {
            return 0;
        }

        @Override
        public void write(OutputCapsule output) {
        }

        @Override
        public void read(InputCapsule input) {
        }
    }

    /** 假资产管理器：按 GUID 从映射表返回固定实例 */
    public static class FakeAssetManager implements AssetManager {
        final Map<String, Asset> assets = new HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Asset> T loadGuid(String guid, Class<T> assetClass) {
            return (T) assets.get(guid);
        }

        @Override
        public <T extends Asset> T loadGuidInstance(String guid, Class<T> assetClass) {
            return loadGuid(guid, assetClass);
        }

        @Override
        public <T extends Asset> T loadAsset(String path, Class<T> assetClass) {
            return null;
        }

        @Override
        public <T extends Asset> T loadAssetInstance(String path, Class<T> assetClass) {
            return null;
        }
    }

    public static class Guild implements Serializable {
        String name;
        ArrayList<PlayerData> members = new ArrayList<>();

        @Override
        public void write(OutputCapsule out) {
            out.write("name", name);
            out.write("members", members);
        }

        @Override
        public void read(InputCapsule in) {
            name = in.readString("name");
            members = in.readArrayList("members", PlayerData.class);
            if (members == null) {
                members = new ArrayList<>();
            }
        }
    }

    public static class PlayerData implements Serializable {
        byte b;
        short s;
        int hp;
        long id;
        float speed;
        double weight;
        boolean alive;
        char initial;
        String name;
        Quality quality;
        Vector3f position;
        Quaternionf rotation;
        Matrix4f matrix;
        int[] scores;
        String[] tags;
        Quality[] qualities;
        ArrayList<String> items;
        ArrayList<Quality> buffs;
        ArrayList<Serializable> backpack;
        ArrayList<String> allNull;
        Guild guild;
        PlayerData partner;
        FakeAsset weapon;
        FakeAsset[] weaponSlots;
        ArrayList<FakeAsset> inventory;

        static PlayerData sample() {
            PlayerData p = new PlayerData();
            p.b = -8;
            p.s = -1600;
            p.hp = 100;
            p.id = 123_456_789_012L;
            p.speed = 3.5f;
            p.weight = 72.25;
            p.alive = true;
            p.initial = '中';
            p.name = "勇者\t\"A\"";
            p.quality = Quality.HIGH;
            p.position = new Vector3f(1.5f, -2.5f, 3.25f);
            p.rotation = new Quaternionf(0.1f, 0.2f, 0.3f, 0.9f);
            p.matrix = new Matrix4f().translationRotateScale(
                new Vector3f(1, 2, 3),
                new Quaternionf().rotateY(0.5f),
                new Vector3f(2, 2, 2));
            p.scores = new int[]{1, -2, 3};
            p.tags = new String[]{"a", null, "c"};
            p.qualities = new Quality[]{Quality.LOW, null, Quality.HIGH};
            p.items = new ArrayList<>();
            p.items.add("sword");
            p.items.add(null);
            p.items.add("shield");
            p.buffs = new ArrayList<>();
            p.buffs.add(Quality.MEDIUM);
            p.buffs.add(Quality.HIGH);
            p.backpack = new ArrayList<>();
            p.backpack.add(new Guild());
            p.allNull = new ArrayList<>();
            p.allNull.add(null);
            p.allNull.add(null);
            return p;
        }

        @Override
        public void write(OutputCapsule out) {
            out.write("b", b);
            out.write("s", s);
            out.write("hp", hp);
            out.write("id", id);
            out.write("speed", speed);
            out.write("weight", weight);
            out.write("alive", alive);
            out.write("initial", initial);
            out.write("name", name);
            out.write("quality", quality);
            out.write("position", position);
            out.write("rotation", rotation);
            out.write("matrix", matrix);
            out.write("scores", scores);
            out.write("tags", tags);
            out.write("qualities", qualities);
            out.writeArrayString("items", items);
            out.writeEnumList("buffs", buffs);
            out.write("backpack", backpack);
            out.writeArrayString("allNull", allNull);
            out.write("guild", guild);
            out.write("partner", partner);
            out.write("weapon", weapon);
            out.write("weaponSlots", weaponSlots);
            out.write("inventory", inventory);
        }

        @Override
        public void read(InputCapsule in) {
            b = in.readByte("b");
            s = in.readShort("s");
            hp = in.readInt("hp");
            id = in.readLong("id");
            speed = in.readFloat("speed");
            weight = in.readDouble("weight");
            alive = in.readBoolean("alive");
            initial = in.readChar("initial");
            name = in.readString("name");
            quality = in.readEnum("quality", Quality.class);
            position = in.readVector3f("position");
            rotation = in.readQuaternionf("rotation");
            matrix = in.readMatrix4f("matrix");
            scores = in.readIntArray("scores");
            tags = in.readStringArray("tags");
            qualities = in.readEnumArray("qualities", Quality.class);
            items = in.readArrayString("items");
            buffs = in.readEnumList("buffs", Quality.class);
            backpack = in.readArrayList("backpack", Serializable.class);
            allNull = in.readArrayString("allNull");
            guild = in.readSerializable("guild", Guild.class);
            partner = in.readSerializable("partner", PlayerData.class);
            weapon = in.readSerializable("weapon", FakeAsset.class);
            weaponSlots = (FakeAsset[]) in.readSerializableArray("weaponSlots", FakeAsset.class);
            inventory = (ArrayList<FakeAsset>) in.readArrayList("inventory", FakeAsset.class);
        }
    }

    /** 字段演进：比 FullData 少字段、多字段 */
    public static class LegacyData implements Serializable {
        int x;
        int y;
        int zNew;

        @Override
        public void write(OutputCapsule out) {
            out.write("x", x);
            out.write("y", y);
            out.write("zNew", zNew);
        }

        @Override
        public void read(InputCapsule in) {
            x = in.readInt("x");
            y = in.readInt("y");
            zNew = in.readInt("zNew");
        }
    }

    public static class FullData implements Serializable {
        int x;
        int y;

        @Override
        public void write(OutputCapsule out) {
            out.write("x", x);
            out.write("y", y);
        }

        @Override
        public void read(InputCapsule in) {
            x = in.readInt("x");
            y = in.readInt("y");
        }
    }

    /** 故意写重复 key */
    public static class BadData implements Serializable {
        @Override
        public void write(OutputCapsule out) {
            out.write("dup", 1);
            out.write("dup", 2);
        }

        @Override
        public void read(InputCapsule in) {
        }
    }

    /** 持有混合类型列表 */
    public static class MixedListData implements Serializable {
        ArrayList<Object> mixed = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public void write(OutputCapsule out) {
            out.write("mixed", (ArrayList) mixed);
        }

        @Override
        public void read(InputCapsule in) {
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  注册（测试不走注解处理器，避免 main/test 双份注册类遮蔽）
    // ═══════════════════════════════════════════════════════════════════════

    @BeforeAll
    static void registerClasses() {
        ReflectiveFactory.register(PlayerData.class.getName(), PlayerData.class, PlayerData::new, PlayerData[]::new);
        ReflectiveFactory.register(Guild.class.getName(), Guild.class, Guild::new, Guild[]::new);
        ReflectiveFactory.register(FullData.class.getName(), FullData.class, FullData::new, FullData[]::new);
        ReflectiveFactory.register(LegacyData.class.getName(), LegacyData.class, LegacyData::new, LegacyData[]::new);
        ReflectiveFactory.register(BadData.class.getName(), BadData.class, BadData::new, BadData[]::new);
        ReflectiveFactory.register(MixedListData.class.getName(), MixedListData.class, MixedListData::new, MixedListData[]::new);
        ReflectiveFactory.register(Quality.class.getName(), Quality.class,
            () -> {
                throw new UnsupportedOperationException("无法实例化枚举");
            },
            Quality[]::new);
        ReflectiveFactory.register(FakeAsset.class.getName(), FakeAsset.class,
            () -> {
                throw new UnsupportedOperationException("Asset 经 AssetManager 加载，不走工厂实例化");
            },
            FakeAsset[]::new);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════════════════════════

    private File tempFile(String name) {
        return tempDir.resolve(name).toFile();
    }

    @SuppressWarnings("unchecked")
    private <T extends Serializable> T writeAndRead(T obj, String fileName) throws Exception {
        File file = tempFile(fileName);
        new BinaryExporter(file).export(obj);
        return (T) new BinaryImporter(file).importData((Class<T>) obj.getClass());
    }

    private static void assertVecEquals(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, 1e-6f);
        assertEquals(expected.y, actual.y, 1e-6f);
        assertEquals(expected.z, actual.z, 1e-6f);
    }

    private static void assertQuatEquals(Quaternionf expected, Quaternionf actual) {
        assertEquals(expected.x, actual.x, 1e-6f);
        assertEquals(expected.y, actual.y, 1e-6f);
        assertEquals(expected.z, actual.z, 1e-6f);
        assertEquals(expected.w, actual.w, 1e-6f);
    }

    private static void assertMatEquals(Matrix4f expected, Matrix4f actual) {
        assertEquals(expected.m00(), actual.m00(), 1e-6f);
        assertEquals(expected.m01(), actual.m01(), 1e-6f);
        assertEquals(expected.m02(), actual.m02(), 1e-6f);
        assertEquals(expected.m03(), actual.m03(), 1e-6f);
        assertEquals(expected.m10(), actual.m10(), 1e-6f);
        assertEquals(expected.m11(), actual.m11(), 1e-6f);
        assertEquals(expected.m12(), actual.m12(), 1e-6f);
        assertEquals(expected.m13(), actual.m13(), 1e-6f);
        assertEquals(expected.m20(), actual.m20(), 1e-6f);
        assertEquals(expected.m21(), actual.m21(), 1e-6f);
        assertEquals(expected.m22(), actual.m22(), 1e-6f);
        assertEquals(expected.m23(), actual.m23(), 1e-6f);
        assertEquals(expected.m30(), actual.m30(), 1e-6f);
        assertEquals(expected.m31(), actual.m31(), 1e-6f);
        assertEquals(expected.m32(), actual.m32(), 1e-6f);
        assertEquals(expected.m33(), actual.m33(), 1e-6f);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  往返测试
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void scalarAndJomlRoundTrip() throws Exception {
        PlayerData p = PlayerData.sample();
        PlayerData r = writeAndRead(p, "scalar.dat");

        assertEquals(p.b, r.b);
        assertEquals(p.s, r.s);
        assertEquals(p.hp, r.hp);
        assertEquals(p.id, r.id);
        assertEquals(p.speed, r.speed, 1e-6f);
        assertEquals(p.weight, r.weight, 1e-9);
        assertTrue(r.alive);
        assertEquals(p.initial, r.initial);
        assertEquals(p.name, r.name);
        assertEquals(p.quality, r.quality);
        assertVecEquals(p.position, r.position);
        assertQuatEquals(p.rotation, r.rotation);
        assertMatEquals(p.matrix, r.matrix);
    }

    @Test
    void arrayRoundTrip() throws Exception {
        PlayerData p = PlayerData.sample();
        PlayerData r = writeAndRead(p, "array.dat");

        assertArrayEquals(p.scores, r.scores);
        assertArrayEquals(p.tags, r.tags);
        assertArrayEquals(p.qualities, r.qualities);
    }

    @Test
    void arrayListRoundTrip() throws Exception {
        PlayerData p = PlayerData.sample();
        PlayerData r = writeAndRead(p, "list.dat");

        assertEquals(p.items, r.items);
        assertEquals(p.buffs, r.buffs);
        assertEquals(p.allNull, r.allNull);
        assertNotNull(r.buffs);
        assertEquals(Quality.MEDIUM, r.buffs.get(0));
        assertEquals(1, r.backpack.size());
        assertInstanceOf(Guild.class, r.backpack.get(0));
    }

    @Test
    void sharedReferencePreserved() throws Exception {
        Guild guild = new Guild();
        guild.name = "guild-1";
        PlayerData p1 = PlayerData.sample();
        PlayerData p2 = PlayerData.sample();
        p1.name = "p1";
        p2.name = "p2";
        p1.guild = guild;
        p2.guild = guild;
        guild.members.add(p1);
        guild.members.add(p2);

        Guild g = writeAndRead(guild, "shared.dat");

        assertEquals("guild-1", g.name);
        assertEquals(2, g.members.size());
        // 同一 Guild 被两个 PlayerData 引用，导入后仍是同一实例
        assertSame(g, g.members.get(0).guild);
        assertSame(g, g.members.get(1).guild);
        assertEquals("p1", g.members.get(0).name);
        assertEquals("p2", g.members.get(1).name);
    }

    @Test
    void circularReferenceResolved() throws Exception {
        PlayerData p1 = PlayerData.sample();
        PlayerData p2 = PlayerData.sample();
        p1.name = "p1";
        p2.name = "p2";
        p1.partner = p2;
        p2.partner = p1;

        PlayerData r = writeAndRead(p1, "circular.dat");

        assertEquals("p1", r.name);
        assertEquals("p2", r.partner.name);
        // 循环引用：p2.partner 指回根实例
        assertSame(r, r.partner.partner);
    }

    @Test
    void missingFieldsReturnDefaults() throws Exception {
        FullData full = new FullData();
        full.x = 5;
        full.y = 7;
        File file = tempFile("legacy.dat");
        new BinaryExporter(file).export(full);

        LegacyData legacy = new BinaryImporter(file).importData(LegacyData.class);

        assertEquals(5, legacy.x);
        assertEquals(7, legacy.y);
        assertEquals(0, legacy.zNew); // 新增字段 → 默认值
    }

    @Test
    void absentFieldsReturnDefaults() throws Exception {
        PlayerData p = new PlayerData(); // 全部字段为 null/0
        p.hp = 42;
        PlayerData r = writeAndRead(p, "absent.dat");

        assertEquals(42, r.hp);
        assertNull(r.name);
        assertNull(r.guild);
        assertNull(r.weapon);
        assertNull(r.scores);
        assertNull(r.items);
        assertFalse(r.alive);
        assertEquals(0, r.b);
        assertNull(r.quality);
        // JOML 默认值
        assertVecEquals(new Vector3f(), r.position);
        assertQuatEquals(new Quaternionf(), r.rotation);
        assertMatEquals(new Matrix4f(), r.matrix);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Asset 引用
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void assetReferencesResolveViaManager() throws Exception {
        FakeAssetManager am = new AssetManagerFixture().manager;
        PlayerData p = PlayerData.sample();
        p.weapon = new FakeAsset("guid-sword");
        p.weaponSlots = new FakeAsset[]{new FakeAsset("guid-bow"), null, new FakeAsset("guid-axe")};
        p.inventory = new ArrayList<>();
        p.inventory.add(new FakeAsset("guid-axe"));
        p.inventory.add(null);

        File file = tempFile("asset.dat");
        new BinaryExporter(file).export(p);
        PlayerData r = new BinaryImporter(file, am).importData(PlayerData.class);

        // 导入的资产实例来自 AssetManager，而非新建
        assertSame(am.assets.get("guid-sword"), r.weapon);
        assertSame(am.assets.get("guid-bow"), r.weaponSlots[0]);
        assertNull(r.weaponSlots[1]);
        assertSame(am.assets.get("guid-axe"), r.weaponSlots[2]);
        assertSame(am.assets.get("guid-axe"), r.inventory.get(0));
        assertNull(r.inventory.get(1));
    }

    @Test
    void assetWithoutManagerThrowsClearly() throws Exception {
        PlayerData p = PlayerData.sample();
        p.weapon = new FakeAsset("guid-sword");
        File file = tempFile("asset-no-manager.dat");
        new BinaryExporter(file).export(p);

        BinaryImporter importer = new BinaryImporter(file);
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> importer.importData(PlayerData.class));
        assertTrue(e.getMessage().contains("AssetManager"));
    }

    /** 同时提供 manager 引用（避免每个测试重复造轮子） */
    private static class AssetManagerFixture {
        final FakeAssetManager manager = new FakeAssetManager();

        AssetManagerFixture() {
            manager.assets.put("guid-sword", new FakeAsset("guid-sword"));
            manager.assets.put("guid-bow", new FakeAsset("guid-bow"));
            manager.assets.put("guid-axe", new FakeAsset("guid-axe"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  校验与防护
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void duplicateKeyRejected() {
        File file = tempFile("dup.dat");
        assertThrows(IllegalStateException.class, () -> new BinaryExporter(file).export(new BadData()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mixedTypeListRejected() {
        MixedListData data = new MixedListData();
        data.mixed.add("text");
        data.mixed.add(new PlayerData());
        File file = tempFile("mixed.dat");
        assertThrows(IllegalStateException.class, () -> new BinaryExporter(file).export(data));
    }

    @Test
    void exporterAndImporterAreReusable() throws Exception {
        File file = tempFile("reuse.dat");
        BinaryExporter exporter = new BinaryExporter(file);
        BinaryImporter importer = new BinaryImporter(file);

        FullData a = new FullData();
        a.x = 1;
        a.y = 2;
        exporter.export(a);
        FullData ra = importer.importData(FullData.class);
        assertEquals(1, ra.x);
        assertEquals(2, ra.y);

        // 同一实例再次导出/导入，状态已重置
        FullData b = new FullData();
        b.x = 10;
        b.y = 20;
        exporter.export(b);
        FullData rb = importer.importData(FullData.class);
        assertEquals(10, rb.x);
        assertEquals(20, rb.y);
    }

    @Test
    void corruptFileFlagRejected() throws Exception {
        File file = tempFile("corrupt.dat");
        new BinaryExporter(file).export(new FullData());
        // 破坏文件头
        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
        bytes[3] = 0x7f;
        java.nio.file.Files.write(file.toPath(), bytes);

        BinaryImporter importer = new BinaryImporter(file);
        assertThrows(java.io.IOException.class, () -> importer.importData(FullData.class));
    }
}
