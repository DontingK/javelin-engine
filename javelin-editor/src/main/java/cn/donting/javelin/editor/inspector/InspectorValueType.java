package cn.donting.javelin.editor.inspector;

/**
 * Inspector 字段值类型：/inspector/info 反射推导后随字段 VO 下发（valueType 字符串），
 * 前端按此选择输入控件并决定 value 的序列化格式；/inspector/setValue 原字符串回传。
 *
 * @author javelin
 * @create 2026/10/8
 **/
public enum InspectorValueType {
    /** int 字段；配 @InsIntRange 时渲染滑条 */
    INT,
    /** int 字段按阴影分辨率语义（@InsShadowResolution）：预设档下拉（256~4096，无 Custom），value=像素值 */
    SHADOW_RESOLUTION,
    /** float 字段；配 @InsFloatRange 时渲染滑条 */
    FLOAT,
    /** Color 字段（"r,g,b,a" 0-1 浮点） */
    COLOR,
    /** Vector3f 字段（"x,y,z"） */
    VEC3_F,
    /** Quaternionf 字段（@InsRotation 标记），按欧拉角度数（"x,y,z"）展示/编辑 */
    ROTATION,
    /** 枚举字段；value = 枚举常量名，显示名走 @InsEnumLabel */
    ENUM,
    /** boolean 字段（"true"/"false"） */
    BOOLEAN,
    /** int 字段按 layer 位掩码语义，勾选式多选（标记注解暂缓） */
    LAYER_MASK,
    /** String 字段（原样字符串） */
    STRING,
    /** Rect 字段（"x,y,width,height"） */
    RECT,
    /** 节点头 Tag 单选（value=tag 名，"Untagged"=清空）；非字段行类型，回写专用通道不经 PropMeta */
    TAG,
    /**
     * Asset 引用型字段（Mesh/Material 等）：value 三态——null=字段无资产（前端
     * "None"）、空串=内存创建（guid 空，前端禁用态、不可回写）、非空=guid 原串；
     * 显示名另经 InspectorFieldVo.assetName 下发（guid 反查源路径尾名）。
     * v0.1 只读展示，赋值通道待项目面板拖拽资产落地。
     */
    GUID_ASSET,
    /**
     * 资产列表字段（List&lt;Asset 子类&gt;，MeshRenderer.materials 首例）：收集面展开为
     * N 行（N = 列表长度，min 1——列表长度由 setMesh/setMaterials 按子网格数对齐），
     * 每行一个元素、行控件与 GUID_ASSET 同款 AssetInput；fieldName 编码序号
     * （materials[i]），回写经整列表 setter（InspectorReflector.applyAssetList）。
     */
    GUID_ASSET_LIST,
}
