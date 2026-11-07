package cn.donting.javelin.core.render.picking;

import cn.donting.javelin.core.JavelinEngine;
import cn.donting.javelin.core.render.RenderCamera;
import cn.donting.javelin.core.render.RenderObject;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.pipeline.pass.PickPass;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.TextureReadbackRing;
import cn.donting.javelin.rhi.types.TextureLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * GPU ID-buffer 拾取系统（Engine_Picking_Design.md，2026-10-23 拍板：pass 本体落
 * core、编辑器与运行时游戏同为消费方，默认不挂、构造后 {@link #enable()} 启用）。
 *
 * <p>职责：拾取 Pass 挂/摘管线 + 帧内 pick ID 分配（PickPass 录制期回调）+
 * 点击/悬停读回调度（{@link TextureReadbackRing} 2 槽异步环，非阻塞 poll）+
 * ID→对象映射（3 帧表环，帧号校验防串帧）。拾取附件（R32_UINT）归
 * {@link RenderingSystem#pickIdFor}。覆盖制（不依赖用户 shader 声明），透明物同深度
 * 语义可拾取（Unity 口径）；hover 结果 1~2 帧延迟（GPU 管线深度固有，静止指针零读回）。</p>
 *
 * <p>线程模型：全程引擎主线程（pointer/requestClick 由消费方 AppState update
 * 喂入、frameEnd 由引擎 mainLoop renderFrame 后调、listener 同线程回调）。
 * 读回时序保证：帧 N 拷贝至迟 N+2 帧首可读（FrameLoop 帧首 fence await + 队列序），
 * 3 帧表环覆盖。</p>
 */
public final class PickSystem implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PickSystem.class);

    /** 帧表环槽数：覆盖「帧 N 结果在 N+2 帧首保证可读」的时序余量 */
    private static final int TABLE_SLOTS = 3;
    /** 单像素 R32_UINT 读回字节数 */
    private static final int PICK_PIXEL_BYTES = 4;

    /** 帧表：帧号 + id→owner（owner = RenderObject.owner，MeshRenderer；null/0 = 背景） */
    private static final class FrameTable {
        long frame = -1;
        final Map<Integer, Object> byId = new HashMap<>();
    }

    private final JavelinEngine engine;
    private final String targetView;
    private final FrameTable[] tables = new FrameTable[TABLE_SLOTS];
    private final TextureReadbackRing ring;
    private final ByteBuffer idBuf = ByteBuffer.allocateDirect(PICK_PIXEL_BYTES)
            .order(ByteOrder.nativeOrder());

    private PickPass pickPass;
    private boolean enabled;
    private boolean closed;

    /** 录制期状态（PickPass 回调内） */
    private long currentFrame = -1;
    private int nextId;

    /** 指针（附件物理像素；pointerValid=false 停发 hover 拷贝） */
    private boolean pointerValid;
    private int pointerX;
    private int pointerY;
    private int lastSubmitX = -1;
    private int lastSubmitY = -1;

    /** 待投递/待收割的点击请求（frame=-1 未投递；≥0 = 已投递帧，收割时对帧号） */
    private int clickX;
    private int clickY;
    private long clickFrame = Long.MIN_VALUE;
    private boolean clickPending;

    private Consumer<Object> hoverListener;
    private Consumer<Object> clickListener;

    /**
     * @param engine      引擎（取 device/renderingSystem/renderPipeline）
     * @param targetView  目标视口名（RenderCamera 名；单视口 v1 口径，多视口后置）
     */
    public PickSystem(JavelinEngine engine, String targetView) {
        if (engine == null || targetView == null || targetView.isEmpty()) {
            throw new IllegalArgumentException("engine / targetView must not be null or empty");
        }
        this.engine = engine;
        this.targetView = targetView;
        this.ring = new TextureReadbackRing(engine.device(), 2, PICK_PIXEL_BYTES);
        engine.setPickSystem(this);
        for (int i = 0; i < TABLE_SLOTS; i++) {
            tables[i] = new FrameTable();
        }
    }

    /** 挂载拾取 Pass（管线尾插；幂等，重复调用抛错防双挂） */
    public void enable() {
        checkOpen();
        if (enabled) {
            throw new IllegalStateException("PickSystem already enabled");
        }
        pickPass = new PickPass(this, engine.device(), engine.getAssetManager());
        engine.renderPipeline().addPass(pickPass);
        enabled = true;
        log.info("PickSystem enabled: view={}", targetView);
    }

    /** 每帧指针位置（附件物理像素；消费方 AppState update 喂入；-1 = 指针不在拾取视口） */
    public void pointer(int px, int py) {
        this.pointerValid = px >= 0 && py >= 0;
        this.pointerX = px;
        this.pointerY = py;
    }

    /**
     * 请求点击拾取（本帧附件像素坐标）：结果经 clickListener 异步送达
     * （1~2 帧后，owner=null 即点空）。期间新点击覆盖旧请求（单飞语义）。
     */
    public void requestClick(int px, int py) {
        this.clickX = px;
        this.clickY = py;
        this.clickPending = true;
        this.clickFrame = Long.MIN_VALUE;
    }

    /** hover 结果监听（owner = RenderObject.owner 或 null；1~2 帧延迟，引擎线程回调） */
    public void setHoverListener(Consumer<Object> listener) {
        this.hoverListener = listener;
    }

    /** 点击结果监听（owner = RenderObject.owner 或 null=点空；引擎线程回调） */
    public void setClickListener(Consumer<Object> listener) {
        this.clickListener = listener;
    }

    /** 目标视口名 */
    public String targetView() {
        return targetView;
    }

    // ═══ PickPass 录制期（图声明/执行，引擎线程） ═══

    /** 帧表重建（同帧多视口调用幂等；图声明期由 PickPass.addToGraph 调） */
    public void beginFrame(long frameIndex) {
        if (currentFrame == frameIndex) {
            return;
        }
        currentFrame = frameIndex;
        nextId = 1;
        FrameTable table = tables[(int) (frameIndex % TABLE_SLOTS)];
        table.frame = frameIndex;
        table.byId.clear();
    }

    /** 分配帧内 pick ID 并登记 id→owner（PickPass 绘制期逐物体调；跨视口全局递增） */
    public int assignPickId(RenderObject ro) {
        int id = nextId++;
        tables[(int) (currentFrame % TABLE_SLOTS)].byId.put(id, ro.owner());
        return id;
    }

    // ═══ 帧驱动（mainLoop renderFrame 后调，引擎线程） ═══

    /**
     * 帧末调度：先收割读回环（FIFO，一帧可能多枚），再投递本帧像素拷贝——
     * 点击优先（槽忙保留下帧重试），hover 仅指针移动时投递（静止零读回）。
     */
    public void frameEnd(long frameIndex) {
        checkOpen();
        if (!enabled) {
            return;
        }
        // ── 收割 ──
        while (ring.poll()) {
            long srcFrame = ring.resultTag();
            idBuf.clear();
            ring.readResult(idBuf);
            idBuf.rewind();
            int pickId = idBuf.getInt();
            Object owner = ownerOf(srcFrame, pickId);
            if (clickPending && srcFrame == clickFrame) {
                clickPending = false;
                if (clickListener != null) {
                    clickListener.accept(owner);
                }
            } else if (hoverListener != null) {
                hoverListener.accept(owner);
            }
        }
        // ── 投递 ──
        Texture pickTex = targetPickTexture();
        if (pickTex == null) {
            return;   // 目标视口不存在（相机摘除/未建）——本帧无拾取面
        }
        if (clickPending) {
            if (inBounds(pickTex, clickX, clickY)) {
                if (ring.submit(pickTex, TextureLayout.COLOR_ATTACHMENT,
                        clickX, clickY, 1, 1, frameIndex)) {
                    clickFrame = frameIndex;   // 已投递，等收割对帧号
                }   // 槽忙（GPU 落后）：保留 clickPending 下帧重试
            } else {
                // 点击在附件外 = 点空：同步送达，不发读回
                clickPending = false;
                if (clickListener != null) {
                    clickListener.accept(null);
                }
            }
        } else if (pointerValid && (pointerX != lastSubmitX || pointerY != lastSubmitY)
                && inBounds(pickTex, pointerX, pointerY)) {
            if (ring.submit(pickTex, TextureLayout.COLOR_ATTACHMENT,
                    pointerX, pointerY, 1, 1, frameIndex)) {
                lastSubmitX = pointerX;
                lastSubmitY = pointerY;
            }
        }
    }

    // ═══ 生命周期 ═══

    /**
     * 关闭：摘 Pass + 销毁 PSO 与读回环。须 device waitIdle 后
     * （JavelinEngine.shutdown 在 frameLoop.close 之后调用）。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (pickPass != null) {
            if (enabled) {
                engine.renderPipeline().removePass(pickPass);
            }
            pickPass.close();
            pickPass = null;
        }
        ring.close();
        enabled = false;
    }

    // ═══ 内部 ═══

    /** 目标视口的拾取附件（视口不存在返回 null，不预创建） */
    private Texture targetPickTexture() {
        RenderingSystem rs = engine.renderingSystem();
        for (RenderCamera cam : rs.cameras()) {
            if (targetView.equals(cam.name())) {
                return rs.pickIdFor(targetView);
            }
        }
        return null;
    }

    private static boolean inBounds(Texture tex, int x, int y) {
        return x >= 0 && y >= 0 && x < tex.desc().width() && y < tex.desc().height();
    }

    /** 帧号校验查表：表被更晚帧覆盖（GPU 落后超保证窗口，理论不发生）→ 丢弃告警 */
    private Object ownerOf(long frameIndex, int pickId) {
        if (pickId == 0) {
            return null;   // 背景/未命中
        }
        FrameTable table = tables[(int) (frameIndex % TABLE_SLOTS)];
        if (table.frame != frameIndex) {
            log.warn("拾取结果帧 {} 的 ID 表已被覆盖，丢弃 pickId={}（GPU 落后超 2 帧？）", frameIndex, pickId);
            return null;
        }
        return table.byId.get(pickId);
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("PickSystem is closed");
        }
    }
}
