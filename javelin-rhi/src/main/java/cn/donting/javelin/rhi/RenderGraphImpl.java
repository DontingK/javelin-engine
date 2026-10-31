package cn.donting.javelin.rhi;

import cn.donting.javelin.rhi.data.RHIClearColor;
import cn.donting.javelin.rhi.data.RHIClearDepthStencil;
import cn.donting.javelin.rhi.data.RHIClearValue;
import cn.donting.javelin.rhi.data.RHILoadOp;
import cn.donting.javelin.rhi.data.RHIViewport;
import cn.donting.javelin.rhi.data.RHIScissor;
import cn.donting.javelin.rhi.exception.RHIException;
import cn.donting.javelin.rhi.types.SampleCount;
import cn.donting.javelin.rhi.types.TextureLayout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * RenderGraph 默认实现：声明收集 + 编译（拓扑 / 屏障计划 / structureHash）+ 执行器。
 * 编译链路为纯 Java（无 Vulkan 调用），可无 GPU 单测（阶段 ④ 验收）。
 */
public final class RenderGraphImpl implements RenderGraphBuilder {

    private final List<PassData> passes = new ArrayList<>();
    private final Set<String> passNames = new HashSet<>();
    private CompiledGraph compiled;
    private long cachedHash;
    private boolean hashValid;

    @Override
    public GraphicsPassBuilder addGraphicsPass(String name, Consumer<PassDef> def) {
        return addPass(name, false, def);
    }

    @Override
    public ComputePassBuilder addComputePass(String name, Consumer<PassDef> def) {
        return addPass(name, true, def);
    }

    private PassHandle addPass(String name, boolean compute, Consumer<PassDef> def) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Pass name must not be null or empty");
        }
        if (def == null) {
            throw new IllegalArgumentException("Pass def consumer must not be null");
        }
        if (!passNames.add(name)) {
            throw new IllegalArgumentException("Duplicate pass name: " + name);
        }
        PassData data = new PassData(name, compute);
        def.accept(data);
        passes.add(data);
        hashValid = false;
        compiled = null;
        return new PassHandle(name);
    }

    @Override
    public long structureHash() {
        if (hashValid) {
            return cachedHash;
        }
        long h = 0x811C9DC5L;
        h = mix(h, passes.size());
        for (PassData p : passes) {
            h = mix(h, p.name.hashCode());
            h = mix(h, p.compute ? 1 : 0);
            h = mix(h, p.graphicsPipeline != null ? p.graphicsPipeline.handle() : 0);
            h = mix(h, p.computePipeline != null ? p.computePipeline.handle() : 0);
            for (CompiledGraph.Attachment a : p.attachments) {
                h = mix(h, a.texture().handle());
                h = mix(h, a.kind().ordinal() + 1);
                h = mix(h, a.loadOp().ordinal() + 1);
                if (a.resolveTexture() != null) {
                    h = mix(h, a.resolveTexture().handle());
                    h = mix(h, 0x2E50L); // resolve 声明标记（与读/Storage 标记区分）
                }
                RHIScissor region = a.clearRegion();
                if (region != null) {
                    h = mix(h, region.x());
                    h = mix(h, region.y());
                    h = mix(h, region.width());
                    h = mix(h, region.height());
                }
            }
            for (Texture t : p.reads) {
                h = mix(h, t.handle());
                h = mix(h, 0x52ADL); // 读声明标记，与写声明区分
            }
            for (Texture t : p.storageReads) {
                h = mix(h, t.handle());
                h = mix(h, 0x5352L); // Storage 读声明标记
            }
            for (Texture t : p.storageWrites) {
                h = mix(h, t.handle());
                h = mix(h, 0x5357L); // Storage 写声明标记
            }
            for (Buffer b : p.bufferReads) {
                h = mix(h, b.handle());
                h = mix(h, 0x4252L); // Buffer 读声明标记，与纹理标记区分
            }
            for (Buffer b : p.bufferWrites) {
                h = mix(h, b.handle());
                h = mix(h, 0x4257L); // Buffer 写声明标记
            }
        }
        cachedHash = h;
        hashValid = true;
        return h;
    }

    private static long mix(long h, long v) {
        h = h * 31 + v;
        h ^= h >>> 32;
        return h;
    }

    @Override
    public CompiledGraph compile() {
        int n = passes.size();

        // --- 依赖推导：RAW / WAW / WAR 均建立声明序边（保守串行，单命令缓冲执行正确性） ---
        Set<Long> edgeSet = new LinkedHashSet<>();
        Map<Long, Integer> writers = new HashMap<>();          // 纹理 handle → 最近写 Pass 声明索引
        Map<Long, List<Integer>> readers = new HashMap<>();    // 纹理 handle → 最近写之后的读 Pass 声明索引
        for (int i = 0; i < n; i++) {
            PassData p = passes.get(i);
            Map<Long, Boolean> usage = new LinkedHashMap<>();  // handle → 是否写（写优先于读）
            for (CompiledGraph.Attachment a : p.attachments) {
                usage.merge(a.texture().handle(), Boolean.TRUE, (oldV, newV) -> Boolean.TRUE);
                if (a.resolveTexture() != null) {
                    // resolve 终点按写参与依赖（WAW：后续直写该纹理的 Pass 排在本 Pass 后）
                    usage.merge(a.resolveTexture().handle(), Boolean.TRUE, (oldV, newV) -> Boolean.TRUE);
                }
            }
            for (Texture t : p.storageWrites) {
                usage.merge(t.handle(), Boolean.TRUE, (oldV, newV) -> Boolean.TRUE);
            }
            for (Texture t : p.reads) {
                usage.merge(t.handle(), Boolean.FALSE, (oldV, newV) -> oldV);
            }
            for (Texture t : p.storageReads) {
                usage.merge(t.handle(), Boolean.FALSE, (oldV, newV) -> oldV);
            }
            // buffer 与纹理共用 writers/readers 表：句柄取反作命名空间（VkImage/VkBuffer
            // 句柄同为 long，规范不保证跨类型不撞键，取反隔离后各自唯一）
            for (Buffer b : p.bufferWrites) {
                usage.merge(~b.handle(), Boolean.TRUE, (oldV, newV) -> Boolean.TRUE);
            }
            for (Buffer b : p.bufferReads) {
                usage.merge(~b.handle(), Boolean.FALSE, (oldV, newV) -> oldV);
            }
            for (Map.Entry<Long, Boolean> e : usage.entrySet()) {
                long handle = e.getKey();
                boolean write = e.getValue();
                Integer w = writers.get(handle);
                if (w != null && w != i) {
                    edgeSet.add(pack(w, i));
                }
                if (write) {
                    List<Integer> rs = readers.get(handle);
                    if (rs != null) {
                        for (int r : rs) {
                            if (r != i) {
                                edgeSet.add(pack(r, i));
                            }
                        }
                    }
                    writers.put(handle, i);
                    readers.remove(handle);
                } else {
                    readers.computeIfAbsent(handle, k -> new ArrayList<>()).add(i);
                }
            }
        }

        // --- 拓扑排序：Kahn，声明序入队（稳定：无依赖时保持声明顺序） ---
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<>());
        }
        int[] indeg = new int[n];
        for (long e : edgeSet) {
            int from = (int) (e >>> 32);
            int to = (int) (long) e;
            adj.get(from).add(to);
            indeg[to]++;
        }
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (indeg[i] == 0) {
                queue.add(i);
            }
        }
        List<Integer> order = new ArrayList<>(n);
        while (!queue.isEmpty()) {
            int node = queue.poll();
            order.add(node);
            for (int to : adj.get(node)) {
                if (--indeg[to] == 0) {
                    queue.add(to);
                }
            }
        }
        if (order.size() < n) {
            throw new RHIException("RenderGraph 存在循环资源依赖，无法拓扑排序（Pass 数: " + n + "）");
        }

        // --- 屏障计划：每 Pass 每纹理一条（同 Pass 合并、写优先于读） ---
        List<CompiledGraph.Pass> compiledPasses = new ArrayList<>(n);
        // buffer 最近一次使用的访问方向（Buffer 身份键，随拓扑序推进）；同步域两端
        // （compute/图形）执行器按 Pass 类型现推，计划只记「是否已有先前使用 + 本 Pass 方向」
        Map<Buffer, Boolean> bufferLastUse = new LinkedHashMap<>();
        for (int idx : order) {
            PassData p = passes.get(idx);
            Map<Long, CompiledGraph.Barrier> barriers = new LinkedHashMap<>();
            for (CompiledGraph.Attachment a : p.attachments) {
                TextureLayout target = a.kind() == CompiledGraph.Attachment.Kind.DEPTH
                        ? TextureLayout.DEPTH_STENCIL_ATTACHMENT
                        : TextureLayout.COLOR_ATTACHMENT;
                barriers.put(a.texture().handle(),
                        new CompiledGraph.Barrier(a.texture(), target, true));
                if (a.resolveTexture() != null) {
                    // resolve 终点写入：屏障到 COLOR_ATTACHMENT（write=true → backbuffer
                    // 终态 PRESENT_SRC 转换与 wroteBackbuffer 判定自动覆盖）
                    barriers.merge(a.resolveTexture().handle(),
                            new CompiledGraph.Barrier(a.resolveTexture(), TextureLayout.COLOR_ATTACHMENT, true),
                            RenderGraphImpl::mergeBarrier);
                }
            }
            for (Texture t : p.storageWrites) {
                barriers.merge(t.handle(),
                        new CompiledGraph.Barrier(t, TextureLayout.GENERAL, true),
                        RenderGraphImpl::mergeBarrier);
            }
            for (Texture t : p.storageReads) {
                barriers.merge(t.handle(),
                        new CompiledGraph.Barrier(t, TextureLayout.GENERAL, false),
                        RenderGraphImpl::mergeBarrier);
            }
            for (Texture t : p.reads) {
                barriers.merge(t.handle(),
                        new CompiledGraph.Barrier(t, TextureLayout.SHADER_READ_ONLY, false),
                        RenderGraphImpl::mergeBarrier);
            }
            // buffer 内存屏障计划：同 Pass 声明合并（写优先）；本帧已有先前使用才出条目
            // （首帧首用无帧内先行者，帧外同步归上传路径保证，见 PassDef.addBufferRead 契约）
            Map<Buffer, Boolean> bufferUsage = new LinkedHashMap<>();
            for (Buffer b : p.bufferWrites) {
                bufferUsage.merge(b, Boolean.TRUE, (oldV, newV) -> Boolean.TRUE);
            }
            for (Buffer b : p.bufferReads) {
                bufferUsage.merge(b, Boolean.FALSE, (oldV, newV) -> oldV);
            }
            List<CompiledGraph.BufferBarrier> bufferBarriers = new ArrayList<>();
            for (Map.Entry<Buffer, Boolean> e : bufferUsage.entrySet()) {
                if (bufferLastUse.containsKey(e.getKey())) {
                    bufferBarriers.add(new CompiledGraph.BufferBarrier(e.getKey(), e.getValue()));
                }
            }
            bufferLastUse.putAll(bufferUsage);
            compiledPasses.add(new CompiledGraph.Pass(
                    idx, p.name, p.compute,
                    p.graphicsPipeline, p.computePipeline,
                    p.viewport, p.scissor,
                    p.attachments, p.reads, p.storageReads, p.storageWrites,
                    p.bufferReads, p.bufferWrites,
                    new ArrayList<>(barriers.values()), bufferBarriers,
                    p.uniforms, p.callback));
        }

        List<CompiledGraph.Edge> edges = new ArrayList<>(edgeSet.size());
        for (long e : edgeSet) {
            edges.add(new CompiledGraph.Edge((int) (e >>> 32), (int) (long) e));
        }

        compiled = new CompiledGraph(structureHash(), compiledPasses, edges);
        return compiled;
    }

    private static long pack(int from, int to) {
        return ((long) from << 32) | (to & 0xFFFFFFFFL);
    }

    /**
     * 屏障合并：写优先于读；同为写时保持先声明者（附件 > Storage 写）；
     * 同为读时 GENERAL（Storage 读）优先于 SHADER_READ_ONLY（采样读）——GENERAL 可同时满足两种着色器读。
     */
    private static CompiledGraph.Barrier mergeBarrier(CompiledGraph.Barrier existing, CompiledGraph.Barrier incoming) {
        if (existing.isWrite()) {
            return existing;
        }
        if (incoming.isWrite()) {
            return incoming;
        }
        return incoming.targetLayout() == TextureLayout.GENERAL ? incoming : existing;
    }

    /**
     * 图缓存命中时的每帧重绑定（18.7-3：只缓存结构，回调闭包等运行时数据每帧更新）。
     * 结构字段（名称 / 声明 / 屏障计划）取自 cached，运行时字段（管线 / 视口 / uniform / 回调）取自当前声明。
     */
    CompiledGraph rebind(CompiledGraph cached) {
        List<CompiledGraph.Pass> rebound = new ArrayList<>(cached.passes().size());
        for (CompiledGraph.Pass p : cached.passes()) {
            PassData src = passes.get(p.declarationIndex());
            rebound.add(new CompiledGraph.Pass(
                    p.declarationIndex(), p.name(), p.compute(),
                    src.graphicsPipeline, src.computePipeline,
                    src.viewport, src.scissor,
                    p.attachments(), p.reads(), p.storageReads(), p.storageWrites(),
                    p.bufferReads(), p.bufferWrites(), p.barriers(), p.bufferBarriers(),
                    src.uniforms, src.callback));
        }
        return new CompiledGraph(cached.structureHash(), rebound, cached.edges());
    }

    /** 声明期 Pass 数（图缓存校验用） */
    int declarationPassCount() {
        return passes.size();
    }

    @Override
    public void execute(CommandBuffer cmd, Texture backbuffer) {
        CompiledGraph g = compiled != null ? compiled : compile();
        execute(cmd, backbuffer, g);
    }

    @Override
    public void execute(CommandBuffer cmd, Texture backbuffer, CompiledGraph compiled) {
        if (cmd == null) {
            throw new IllegalArgumentException("CommandBuffer must not be null");
        }
        if (compiled == null) {
            throw new IllegalArgumentException("CompiledGraph must not be null");
        }
        boolean hasBackbuffer = backbuffer != null;
        long backbufferHandle = hasBackbuffer ? backbuffer.handle() : 0;

        cmd.begin();
        try {
            LayoutTracker tracker = new LayoutTracker();
            if (hasBackbuffer) {
                tracker.set(backbufferHandle, TextureLayout.PRESENT_SRC);
            }
            // buffer 句柄 → {本帧最近使用是否计算域, 是否含写}——屏障源端同步域现推用
            Map<Long, boolean[]> bufferLastUse = new HashMap<>();
            boolean wroteBackbuffer = false;

            for (CompiledGraph.Pass pass : compiled.passes()) {
                // 屏障插入：目标布局 ≠ 当前布局才插（冗余消除，24.3）
                for (CompiledGraph.Barrier b : pass.barriers()) {
                    long handle = b.texture().handle();
                    TextureLayout current = tracker.get(handle);
                    if (current != b.targetLayout()) {
                        cmd.pipelineBarrier(b.texture(), current, b.targetLayout(),
                                b.isWrite(), pass.compute());
                        tracker.set(handle, b.targetLayout());
                    }
                    if (hasBackbuffer && handle == backbufferHandle && b.isWrite()) {
                        wroteBackbuffer = true;
                    }
                }

                // buffer 内存屏障：源端域/方向按本帧最近使用现推（计划保证只有非首用才有条目）；
                // 同 Pass 读写并存按写收尾（先读登记、写覆盖）
                for (CompiledGraph.BufferBarrier b : pass.bufferBarriers()) {
                    boolean[] last = bufferLastUse.get(b.buffer().handle());
                    if (last != null) {
                        cmd.bufferBarrier(b.buffer(), last[0], last[1], pass.compute(), b.isWrite());
                    }
                }
                for (Buffer b : pass.bufferReads()) {
                    bufferLastUse.putIfAbsent(b.handle(), new boolean[]{pass.compute(), false});
                }
                for (Buffer b : pass.bufferWrites()) {
                    bufferLastUse.put(b.handle(), new boolean[]{pass.compute(), true});
                }

                // 渲染通道包裹（dynamic rendering）：图形 Pass 有附件时自动 begin/end
                List<CompiledGraph.Attachment> colorAttachments = null;
                CompiledGraph.Attachment depthAttachment = null;
                if (!pass.compute()) {
                    for (CompiledGraph.Attachment a : pass.attachments()) {
                        if (a.kind() == CompiledGraph.Attachment.Kind.DEPTH) {
                            depthAttachment = a;
                        } else {
                            if (colorAttachments == null) {
                                colorAttachments = new ArrayList<>();
                            }
                            colorAttachments.add(a);
                        }
                    }
                    if (colorAttachments != null || depthAttachment != null) {
                        cmd.beginRenderPass(colorAttachments, depthAttachment);
                    }
                }

                try {
                    if (pass.viewport() != null) {
                        cmd.setViewport(pass.viewport());
                    }
                    if (pass.scissor() != null) {
                        cmd.setScissor(pass.scissor());
                    }
                    if (pass.compute()) {
                        if (pass.computePipeline() != null) {
                            cmd.bindPipeline(pass.computePipeline());
                        }
                    } else if (pass.graphicsPipeline() != null) {
                        cmd.bindPipeline(pass.graphicsPipeline());
                    }
                    if (pass.callback() != null) {
                        pass.callback().accept(cmd);
                    }
                } finally {
                    if (!pass.compute() && (colorAttachments != null || depthAttachment != null)) {
                        cmd.endRenderPass();
                    }
                }
            }

            // 图尾：backbuffer 写完后转回 PRESENT_SRC 供 present（23.6-2）
            if (hasBackbuffer && wroteBackbuffer) {
                TextureLayout current = tracker.get(backbufferHandle);
                if (current != TextureLayout.PRESENT_SRC) {
                    cmd.pipelineBarrier(backbuffer, current, TextureLayout.PRESENT_SRC);
                    tracker.set(backbufferHandle, TextureLayout.PRESENT_SRC);
                }
            }
        } finally {
            cmd.end();
        }
    }

    /** Pass 声明数据（PassDef 的实现载体，编译期快照进 CompiledGraph） */
    static final class PassData implements PassDef {

        final String name;
        final boolean compute;
        GraphicsPipeline graphicsPipeline;
        ComputePipeline computePipeline;
        RHIViewport viewport;
        RHIScissor scissor;
        final List<CompiledGraph.Attachment> attachments = new ArrayList<>();
        final List<Texture> reads = new ArrayList<>();
        final List<Texture> storageReads = new ArrayList<>();
        final List<Texture> storageWrites = new ArrayList<>();
        final List<Buffer> bufferReads = new ArrayList<>();
        final List<Buffer> bufferWrites = new ArrayList<>();
        final Map<String, float[]> uniforms = new LinkedHashMap<>();
        Consumer<CommandBuffer> callback;

        PassData(String name, boolean compute) {
            this.name = name;
            this.compute = compute;
        }

        @Override
        public void setPipeline(GraphicsPipeline pipeline) {
            if (pipeline == null) {
                throw new IllegalArgumentException("GraphicsPipeline must not be null");
            }
            this.graphicsPipeline = pipeline;
        }

        @Override
        public void setPipeline(ComputePipeline pipeline) {
            if (pipeline == null) {
                throw new IllegalArgumentException("ComputePipeline must not be null");
            }
            this.computePipeline = pipeline;
        }

        @Override
        public void addColorOutput(Texture texture, RHILoadOp loadOp, RHIClearValue clearValue) {
            checkAttachmentArgs(texture, loadOp, clearValue == null);
            attachments.add(new CompiledGraph.Attachment(
                    texture, CompiledGraph.Attachment.Kind.COLOR, loadOp, clearValue));
        }

        @Override
        public void addColorOutput(Texture texture, RHILoadOp loadOp, RHIClearColor clearValue,
                                   RHIScissor clearRegion) {
            checkAttachmentArgs(texture, loadOp, clearValue == null);
            if (clearRegion == null) {
                throw new IllegalArgumentException("Clear region must not be null (use the 3-arg overload for full clears)");
            }
            if (loadOp != RHILoadOp.CLEAR) {
                throw new IllegalArgumentException("Clear region is only allowed with LoadOp CLEAR");
            }
            attachments.add(new CompiledGraph.Attachment(
                    texture, CompiledGraph.Attachment.Kind.COLOR, loadOp, clearValue, clearRegion));
        }

        @Override
        public void addDepthOutput(Texture texture, RHILoadOp loadOp, RHIClearDepthStencil clearValue) {
            checkAttachmentArgs(texture, loadOp, clearValue == null);
            attachments.add(new CompiledGraph.Attachment(
                    texture, CompiledGraph.Attachment.Kind.DEPTH, loadOp, clearValue));
        }

        @Override
        public void addColorOutput(Texture texture, RHILoadOp loadOp, RHIClearColor clearValue,
                                   Texture resolveTarget) {
            checkAttachmentArgs(texture, loadOp, clearValue == null);
            if (resolveTarget == null) {
                throw new IllegalArgumentException("Resolve target must not be null");
            }
            if (resolveTarget == texture) {
                throw new IllegalArgumentException("Resolve target must differ from the MSAA attachment");
            }
            if (texture.desc().sampleCount() == SampleCount.COUNT_1) {
                throw new IllegalArgumentException(
                        "MSAA resolve requires a multisampled attachment, got COUNT_1: " + texture);
            }
            if (resolveTarget.desc().sampleCount() != SampleCount.COUNT_1) {
                throw new IllegalArgumentException("Resolve target must be single-sampled (COUNT_1): "
                        + resolveTarget);
            }
            if (resolveTarget.desc().width() != texture.desc().width()
                    || resolveTarget.desc().height() != texture.desc().height()) {
                throw new IllegalArgumentException("Resolve target dimensions must match the MSAA attachment: "
                        + texture.desc().width() + "x" + texture.desc().height() + " vs "
                        + resolveTarget.desc().width() + "x" + resolveTarget.desc().height());
            }
            attachments.add(new CompiledGraph.Attachment(
                    texture, CompiledGraph.Attachment.Kind.COLOR, loadOp, clearValue, null, resolveTarget));
        }

        private void checkAttachmentArgs(Texture texture, RHILoadOp loadOp, boolean clearNull) {
            if (texture == null) {
                throw new IllegalArgumentException("Attachment texture must not be null");
            }
            if (loadOp == null) {
                throw new IllegalArgumentException("LoadOp must not be null");
            }
            if (loadOp == RHILoadOp.CLEAR && clearNull) {
                throw new IllegalArgumentException("LoadOp CLEAR requires a clear value");
            }
            if (loadOp != RHILoadOp.CLEAR && !clearNull) {
                throw new IllegalArgumentException("Clear value is only allowed with LoadOp CLEAR");
            }
        }

        @Override
        public void addTextureRead(Texture texture) {
            if (texture == null) {
                throw new IllegalArgumentException("Read texture must not be null");
            }
            reads.add(texture);
        }

        @Override
        public void addTextureStorageRead(Texture texture) {
            if (texture == null) {
                throw new IllegalArgumentException("Storage read texture must not be null");
            }
            storageReads.add(texture);
        }

        @Override
        public void addTextureStorageWrite(Texture texture) {
            if (texture == null) {
                throw new IllegalArgumentException("Storage write texture must not be null");
            }
            storageWrites.add(texture);
        }

        @Override
        public void addBufferRead(Buffer buffer) {
            if (buffer == null) {
                throw new IllegalArgumentException("Buffer read must not be null");
            }
            bufferReads.add(buffer);
        }

        @Override
        public void addBufferWrite(Buffer buffer) {
            if (buffer == null) {
                throw new IllegalArgumentException("Buffer write must not be null");
            }
            bufferWrites.add(buffer);
        }

        @Override
        public void setViewport(RHIViewport viewport) {
            if (viewport == null) {
                throw new IllegalArgumentException("Viewport must not be null");
            }
            this.viewport = viewport;
        }

        @Override
        public void setScissor(RHIScissor scissor) {
            if (scissor == null) {
                throw new IllegalArgumentException("Scissor must not be null");
            }
            this.scissor = scissor;
        }

        @Override
        public void setUniform(String name, float[] data) {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Uniform name must not be null or empty");
            }
            if (data == null) {
                throw new IllegalArgumentException("Uniform data must not be null");
            }
            uniforms.put(name, Arrays.copyOf(data, data.length));
        }

        @Override
        public void setRenderCallback(Consumer<CommandBuffer> callback) {
            if (callback == null) {
                throw new IllegalArgumentException("Render callback must not be null");
            }
            this.callback = callback;
        }
    }

    /** Pass 句柄（GraphicsPassBuilder / ComputePassBuilder 共用实现） */
    private static final class PassHandle implements GraphicsPassBuilder, ComputePassBuilder {

        private final String name;

        PassHandle(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }
    }
}
