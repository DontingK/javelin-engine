# Serialize 序列化系统设计文档

> 本文档描述 `javelin-core` 中 `com.javelin.core.serialize` 包的序列化系统架构。
> 系统为自研 key-value 胶囊式 API + 二进制后端实现，支持对象图导出/导入、
> 循环引用与共享引用，全程零反射实例化（配合 `javelin-ast` 编译时注册）。

## 设计目标

- 对象只需实现 `Serializable` 接口即可读写，按 key 存取，**字段顺序无关**
- 支持完整对象图：嵌套对象、数组、`ArrayList`、枚举、JOML 数学类型
- 支持循环引用与共享引用（同一对象多次引用，导入后仍是同一实例）
- 零反射：反序列化实例化全部走 `ReflectiveFactory` 编译时注册表（Native Image 兼容）
- `Asset` 引用特殊处理：只序列化 GUID，导入时经 `AssetManager` 按 GUID 加载

## 分层结构

```
┌─────────────────────────────────────────────┐
│  API 层（com.javelin.core.serialize）         │
│    Serializable  —  write(OutputCapsule)     │
│                    read(InputCapsule)        │
│    OutputCapsule / InputCapsule — key 读写   │
│    Exporter.export() / Importer.importData() │
├─────────────────────────────────────────────┤
│  二进制后端（.serialize.binary）              │
│    BinaryExporter / BinaryImporter — 对象图   │
│    BinaryOutputCapsule / BinaryInputCapsule  │
│    BinaryArrayOutput / BinaryArrayListOutput │
│    BinaryStreamOutput / BinaryStreamInput    │
├─────────────────────────────────────────────┤
│  实例化支撑                                   │
│    ReflectiveFactory（@Reflective 编译时注册）│
│    AssetManager（Asset GUID 解析）            │
└─────────────────────────────────────────────┘
```

## 核心接口

### Serializable

```java
public interface Serializable {
    void write(OutputCapsule output);
    void read(InputCapsule input);
}
```

- 标注 `@Reflective`，由 `javelin-ast` 注解处理器在编译时注册到 `ReflectiveFactory`
- `write` / `read` 使用**相同的 key**，顺序可以不同（key-based 存取）

### OutputCapsule / InputCapsule

按 key 读写的字段容器，覆盖：

- 9 种基本类型 + String
- `Serializable` 对象引用、Enum（存 `name()`）
- 9 种基本类型数组、String[]、`Serializable[]`、Enum[]
- `ArrayList<T extends Serializable>`、`ArrayList<String>`
- JOML：`Vector3f` / `Quaternionf` / `Matrix4f`（后端决定紧凑格式）

**null 语义**：写出时 null 值直接跳过（key 不存在）；读取时返回类型默认值
（数值 0、boolean false、引用 null；Vector3f 零向量、Quaternionf 单位四元数、Matrix4f 单位矩阵）。

**重复 key 防护**：`BinaryOutputCapsule` 用 HashSet 检测同一对象内重复 key，抛 `IllegalStateException`。

## 二进制文件格式

大端序（`DataOutputStream`）。String 编码：int 长度 + UTF-8 字节，`-1` 表示 null。

```
┌──────────────────────────────────────────────────┐
│ 文件头：int magic = 0x0411（FILE_FLAG_SERIALIZABLE）│
│ 对象数量：int N                                     │
├──────────────────────────────────────────────────┤
│ 对象头区 × N：                                      │
│   int id                                           │
│   byte 类型：1=Serializable  2=数组  3=ArrayList     │
│   类型 1：String 类全名                             │
│   类型 2：String 元素类名 + int 长度                 │
│   类型 3：String 元素类名（首元素类，全 null 为 null）│
│          + int 大小                                 │
├──────────────────────────────────────────────────┤
│ 对象数据区 × N：int id + 负载                        │
└──────────────────────────────────────────────────┘
```

### 对象数据（BinaryOutputCapsule）

```
int fieldCount
字段 × fieldCount：
  String key（UTF-8）
  byte typeTag：1..15（byte/short/int/long/float/double/boolean/char/
                String/OBJECT/ENUM/ASSET/Vector3f/Quaternionf/Matrix4f）
  值：
    基本类型      — 定长原值
    String/Enum   — UTF-8 字符串（Enum 存 name()）
    OBJECT        — int 对象 id（-1 = null）
    ASSET         — String guid
    Vector3f      — 3 × float（12 字节）
    Quaternionf   — 4 × float（16 字节）
    Matrix4f      — 16 × float，列优先（64 字节）
```

### 数组 / ArrayList 数据

数组模式由**元素类型**决定，ArrayList 模式由**首个非 null 元素**决定：

| 模式 | 适用元素 | 编码 |
|------|---------|------|
| OBJECT_REFS（1） | `Serializable` | 逐个写 int 对象 id（支持 null = -1） |
| STRING_VALUES（2） | `String` | 逐个写字符串（null 写 -1） |
| ASSET_REFS（3） | `Asset` | 逐个写 GUID 字符串 |
| DIRECT_VALUES（2，仅数组） | 基本类型 / Enum | 内联写值（Enum 存 name()，null 写 -1） |
| ENUM_VALUES（4，仅 ArrayList） | `Enum` | 逐个写 name()，元素类名记录在对象头区 |

**类型校验**：写出前校验全部非 null 元素与首元素类型一致，不一致立即抛
`IllegalStateException`（杜绝静默丢数据）；不支持的元素类型抛
`IllegalArgumentException`。全 null 的 ArrayList 按 OBJECT_REFS 全 -1 写出，可无损往返。

## 导出流程（BinaryExporter）

两阶段设计：

1. **收集（collect）**：从根对象开始遍历对象图。每个对象经 `IdentityHashMap`
   去重并分配递增 id，注册为 `BinaryOutput`（capsule / array / arraylist）。
   递归调用子对象注册——同一对象多处引用只注册一次，天然支持循环引用。
2. **写入**：先写文件头 + 对象头区（id + 类型描述），再写对象数据区。

**原子写**：先写同目录临时文件，`getFD().sync()` 后 `ATOMIC_MOVE` 替换目标
（不支持原子移动时退化为普通 move，最多重试 5 次）；失败清理临时文件与新建的目标文件。

## 导入流程（BinaryImporter）

与导出对称的两阶段，保证循环引用可解析：

1. **读对象头，预创建全部实例**：类名经 `ReflectiveFactory.newInstance()` /
   `newArray()` 零反射创建，按 id 存入 `objectCache`。
   （此时对象字段都是默认值，但实例身份已存在，前向/循环引用可以指向它。）
2. **读对象数据**：逐对象读取字段，OBJECT 引用按 id 从 `objectCache` 解析，
   最后调用 `value.read(capsule)` 填充。

返回值：id 为 0 的对象，即导出时的根对象。
**根对象按 `importData(Class)` 传入的类型实例化**（而非文件记录的类名），支持用新类读取旧文件做字段演进迁移；嵌套对象仍按文件类名实例化。

**Asset 解析**：字段类型为 `Asset` 子类时，读取存的是 GUID，
经 `importer.getAssetManager().loadGuid(guid, class)` 从资产库加载真实资产。

## Asset 引用

| 方向 | 行为 |
|------|------|
| 写出 | `value instanceof Asset` → TYPE_ASSET + `asset.getGuid()`；`Asset[]` / `ArrayList<Asset>` → ASSET_REFS 模式逐元素写 GUID（均不嵌入资产内容） |
| 读入 | 单字段经 `readSerializable` 检测 Asset 类型 → `BinaryImporter.loadAsset()` 按 GUID 加载；数组/列表按元素类型走 `loadAsset` 解析 |

因此 `BinaryImporter` 带 `AssetManager` 的构造器；无资产引用的纯数据文件可用单参构造。
数据含 Asset 引用但未提供 `AssetManager` 时抛 `IllegalStateException`（明确提示，而非 NPE）。

## 使用示例

```java
// 导出
BinaryExporter exporter = new BinaryExporter(new File("save.dat"));
exporter.export(myObject);

// 导入
BinaryImporter importer = new BinaryImporter(new File("save.dat"), assetManager);
MyClass obj = importer.importData(MyClass.class);
```

```java
public class PlayerData implements Serializable {
    @Override
    public void write(OutputCapsule out) {
        out.write("name", name);
        out.write("hp", hp);
        out.write("position", position);          // Vector3f
        out.write("weapon", weapon);              // Serializable 或 Asset
        out.write("tags", new ArrayList<>(tags)); // ArrayList<String>
    }
    @Override
    public void read(InputCapsule in) {
        name = in.readString("name");
        hp = in.readInt("hp");
        position = in.readVector3f("position");
        weapon = in.readSerializable("weapon", Weapon.class);
        tags = in.readArrayString("tags");
    }
}
```

## 关键约定与限制

- **字段演进**：key-based 读取容忍新增字段（读不到返回默认值）与删除字段（跳过未知 key 依赖类型 tag 正常解码）；无 schema 版本号机制
- **共享引用保真**：基于对象身份的引用表使导入后共享/循环引用结构完整保留
- **ArrayList 元素类型一致性**：写出前强制校验元素类型一致，混合类型列表抛出异常；Enum 元素经对象头区的元素类名精确重建（不再是 String）
- **类必须注册**：所有出现在对象图中的 `Serializable` 具体类（含数组元素类、ArrayList 元素类）都必须标注 `@Reflective`，否则反序列化抛"未注册的类"
- **编码**：所有文本（key、类名、字符串值）UTF-8
- 数值读取用 `Number` 转型，跨数值类型读取（如 float 字段读 int 值）兼容
- `BinaryExporter` / `BinaryImporter` 实例可复用：每次 `export()` / `importData()` 前自动重置内部状态（id 计数器、对象引用表）

## 与其他模块的关系

| 模块 | 关系 |
|------|------|
| `javelin-ast` | `@Reflective` 注解 + 编译期生成 `ReflectiveRegistration`，为零反射实例化提供注册表 |
| `javelin-core/assets` | `Asset` GUID 引用的写出与 `AssetManager.loadGuid` 解析 |
| `javelin-core/scene` | 组件可实现 `Serializable` 参与场景序列化（场景级序列化器为后续迭代） |
