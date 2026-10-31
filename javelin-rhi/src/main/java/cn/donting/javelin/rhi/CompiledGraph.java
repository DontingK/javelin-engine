package cn.donting.javelin.rhi;

import cn.donting.javelin.rhi.data.RHIClearValue;
import cn.donting.javelin.rhi.data.RHILoadOp;
import cn.donting.javelin.rhi.data.RHIViewport;
import cn.donting.javelin.rhi.data.RHIScissor;
import cn.donting.javelin.rhi.types.TextureLayout;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 编译后的渲染图（18 节）：拓扑序 + 屏障计划 + 结构哈希。
 * 纯数据对象，不持有 Vulkan 状态；可跨帧复用（仅结构不变时，见 RenderGraphCache）。
 */
public final class CompiledGraph {

    private final long structureHash;
    private final List<Pass> passes;   // 拓扑执行序
    private final List<Edge> edges;    // 依赖边（声明索引，仅导出调试用）

    CompiledGraph(long structureHash, List<Pass> passes, List<Edge> edges) {
        this.structureHash = structureHash;
        this.passes = List.copyOf(passes);
        this.edges = List.copyOf(edges);
    }

    public long structureHash() {
        return structureHash;
    }

    /** 拓扑执行序的 Pass 列表 */
    public List<Pass> passes() {
        return passes;
    }

    /** 依赖边（声明索引 from→to） */
    public List<Edge> edges() {
        return edges;
    }

    /** 导出 DOT 图（Graphviz 可视化，13.3） */
    public String exportDot() {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph RenderGraph {\n");
        sb.append("  rankdir=LR;\n");
        for (Pass p : passes) {
            sb.append("  ").append(dotId(p)).append(" [label=\"")
                    .append(escape(p.name())).append(p.compute() ? " (compute)" : "").append("\"];\n");
        }
        Map<Integer, Pass> byDeclaration = new java.util.HashMap<>();
        for (Pass p : passes) {
            byDeclaration.put(p.declarationIndex(), p);
        }
        for (Edge e : edges) {
            sb.append("  ").append(dotId(byDeclaration.get(e.from()))).append(" -> ")
                    .append(dotId(byDeclaration.get(e.to()))).append(";\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String dotId(Pass p) {
        return "p" + p.declarationIndex();
    }

    private static String escape(String s) {
        return s.replace("\"", "'");
    }

    /** 导出 JSON 完整帧信息（13.3） */
    public String exportJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"structureHash\": ").append(structureHash).append(",\n  \"passes\": [\n");
        for (int i = 0; i < passes.size(); i++) {
            Pass p = passes.get(i);
            sb.append("    {\"name\": \"").append(escape(p.name())).append("\", ");
            sb.append("\"type\": \"").append(p.compute() ? "compute" : "graphics").append("\", ");
            sb.append("\"declarationIndex\": ").append(p.declarationIndex()).append(", ");
            sb.append("\"attachments\": [");
            for (int j = 0; j < p.attachments().size(); j++) {
                Attachment a = p.attachments().get(j);
                if (j > 0) sb.append(", ");
                sb.append("{\"texture\": ").append(a.texture().handle())
                        .append(", \"kind\": \"").append(a.kind()).append("\"")
                        .append(", \"loadOp\": \"").append(a.loadOp()).append("\"");
                if (a.clearRegion() != null) {
                    sb.append(", \"clearRegion\": \"").append(a.clearRegion()).append("\"");
                }
                sb.append("}");
            }
            sb.append("], \"storageReads\": [");
            for (int j = 0; j < p.storageReads().size(); j++) {
                if (j > 0) sb.append(", ");
                sb.append(p.storageReads().get(j).handle());
            }
            sb.append("], \"storageWrites\": [");
            for (int j = 0; j < p.storageWrites().size(); j++) {
                if (j > 0) sb.append(", ");
                sb.append(p.storageWrites().get(j).handle());
            }
            sb.append("], \"reads\": [");
            for (int j = 0; j < p.reads().size(); j++) {
                if (j > 0) sb.append(", ");
                sb.append(p.reads().get(j).handle());
            }
            sb.append("], \"bufferReads\": [");
            for (int j = 0; j < p.bufferReads().size(); j++) {
                if (j > 0) sb.append(", ");
                sb.append(p.bufferReads().get(j).handle());
            }
            sb.append("], \"bufferWrites\": [");
            for (int j = 0; j < p.bufferWrites().size(); j++) {
                if (j > 0) sb.append(", ");
                sb.append(p.bufferWrites().get(j).handle());
            }
            sb.append("], \"barriers\": [");
            for (int j = 0; j < p.barriers().size(); j++) {
                Barrier b = p.barriers().get(j);
                if (j > 0) sb.append(", ");
                sb.append("{\"texture\": ").append(b.texture().handle())
                        .append(", \"targetLayout\": \"").append(b.targetLayout()).append("\"")
                        .append(", \"write\": ").append(b.isWrite()).append("}");
            }
            sb.append("], \"bufferBarriers\": [");
            for (int j = 0; j < p.bufferBarriers().size(); j++) {
                BufferBarrier b = p.bufferBarriers().get(j);
                if (j > 0) sb.append(", ");
                sb.append("{\"buffer\": ").append(b.buffer().handle())
                        .append(", \"write\": ").append(b.isWrite()).append("}");
            }
            sb.append("]}");
            if (i + 1 < passes.size()) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    /** 编译后的单个 Pass（拓扑序中的一帧执行单元） */
    public static final class Pass {

        private final int declarationIndex;
        private final String name;
        private final boolean compute;
        private final GraphicsPipeline graphicsPipeline;
        private final ComputePipeline computePipeline;
        private final RHIViewport viewport;
        private final RHIScissor scissor;
        private final List<Attachment> attachments;
        private final List<Texture> reads;
        private final List<Texture> storageReads;
        private final List<Texture> storageWrites;
        private final List<Buffer> bufferReads;
        private final List<Buffer> bufferWrites;
        private final List<Barrier> barriers;
        private final List<BufferBarrier> bufferBarriers;
        private final Map<String, float[]> uniforms;
        private final Consumer<CommandBuffer> callback;

        Pass(int declarationIndex, String name, boolean compute,
             GraphicsPipeline graphicsPipeline, ComputePipeline computePipeline,
             RHIViewport viewport, RHIScissor scissor,
             List<Attachment> attachments, List<Texture> reads,
             List<Texture> storageReads, List<Texture> storageWrites,
             List<Buffer> bufferReads, List<Buffer> bufferWrites,
             List<Barrier> barriers, List<BufferBarrier> bufferBarriers,
             Map<String, float[]> uniforms, Consumer<CommandBuffer> callback) {
            this.declarationIndex = declarationIndex;
            this.name = name;
            this.compute = compute;
            this.graphicsPipeline = graphicsPipeline;
            this.computePipeline = computePipeline;
            this.viewport = viewport;
            this.scissor = scissor;
            this.attachments = List.copyOf(attachments);
            this.reads = List.copyOf(reads);
            this.storageReads = List.copyOf(storageReads);
            this.storageWrites = List.copyOf(storageWrites);
            this.bufferReads = List.copyOf(bufferReads);
            this.bufferWrites = List.copyOf(bufferWrites);
            this.barriers = List.copyOf(barriers);
            this.bufferBarriers = List.copyOf(bufferBarriers);
            this.uniforms = Map.copyOf(uniforms);
            this.callback = callback;
        }

        public int declarationIndex() {
            return declarationIndex;
        }

        public String name() {
            return name;
        }

        public boolean compute() {
            return compute;
        }

        public GraphicsPipeline graphicsPipeline() {
            return graphicsPipeline;
        }

        public ComputePipeline computePipeline() {
            return computePipeline;
        }

        public RHIViewport viewport() {
            return viewport;
        }

        public RHIScissor scissor() {
            return scissor;
        }

        /** 附件输出（写声明） */
        public List<Attachment> attachments() {
            return attachments;
        }

        /** 纹理采样读取（读：Sampled） */
        public List<Texture> reads() {
            return reads;
        }

        /** 存储纹理读取（读：Storage） */
        public List<Texture> storageReads() {
            return storageReads;
        }

        /** 存储纹理写入（写：Storage） */
        public List<Texture> storageWrites() {
            return storageWrites;
        }

        /** Buffer 读取声明（storage buffer 读，参与依赖推导与内存屏障计划） */
        public List<Buffer> bufferReads() {
            return bufferReads;
        }

        /** Buffer 写入声明（storage buffer 写，参与依赖推导与内存屏障计划） */
        public List<Buffer> bufferWrites() {
            return bufferWrites;
        }

        /** 本 Pass 的屏障计划（按纹理去重、合并后的目标布局） */
        public List<Barrier> barriers() {
            return barriers;
        }

        /** 本 Pass 的 Buffer 内存屏障计划（按句柄去重；读写语义参与同步域推导） */
        public List<BufferBarrier> bufferBarriers() {
            return bufferBarriers;
        }

        /** 构建期 uniform 记录（执行器暂不解析，见 PassDef.setUniform） */
        public Map<String, float[]> uniforms() {
            return uniforms;
        }

        /** 渲染回调（录制阶段调用） */
        public Consumer<CommandBuffer> callback() {
            return callback;
        }
    }

    /** 附件输出声明 */
    public static final class Attachment {

        /** 附件类型 */
        public enum Kind {
            COLOR,   // 颜色附件
            DEPTH    // 深度附件
        }

        private final Texture texture;
        private final Kind kind;
        private final RHILoadOp loadOp;
        private final RHIClearValue clearValue;
        private final RHIScissor clearRegion;
        /** resolve 终点（仅 MSAA 色彩附件非 null：通道结束硬件降采样写入；v0.1 深度不做 resolve） */
        private final Texture resolveTexture;

        public Attachment(Texture texture, Kind kind, RHILoadOp loadOp, RHIClearValue clearValue) {
            this(texture, kind, loadOp, clearValue, null, null);
        }

        public Attachment(Texture texture, Kind kind, RHILoadOp loadOp, RHIClearValue clearValue,
                          RHIScissor clearRegion) {
            this(texture, kind, loadOp, clearValue, clearRegion, null);
        }

        public Attachment(Texture texture, Kind kind, RHILoadOp loadOp, RHIClearValue clearValue,
                          RHIScissor clearRegion, Texture resolveTexture) {
            this.texture = texture;
            this.kind = kind;
            this.loadOp = loadOp;
            this.clearValue = clearValue;
            this.clearRegion = clearRegion;
            this.resolveTexture = resolveTexture;
        }

        public Texture texture() {
            return texture;
        }

        public Kind kind() {
            return kind;
        }

        public RHILoadOp loadOp() {
            return loadOp;
        }

        public RHIClearValue clearValue() {
            return clearValue;
        }

        /**
         * 清屏区域（framebuffer 坐标）；非 null 时 loadOp 的 CLEAR 只作用于该矩形，
         * 由执行器以 vkCmdClearAttachments 实现（矩形外保留原内容）。
         */
        public RHIScissor clearRegion() {
            return clearRegion;
        }

        /** resolve 终点（null = 无 resolve；见构造 javadoc） */
        public Texture resolveTexture() {
            return resolveTexture;
        }
    }

    /** 屏障计划条目：某 Pass 前资源需到达的目标布局 */
    public static final class Barrier {

        private final Texture texture;
        private final TextureLayout targetLayout;
        private final boolean write;

        Barrier(Texture texture, TextureLayout targetLayout, boolean write) {
            this.texture = texture;
            this.targetLayout = targetLayout;
            this.write = write;
        }

        public Texture texture() {
            return texture;
        }

        public TextureLayout targetLayout() {
            return targetLayout;
        }

        /** true = 本 Pass 写（附件）；false = 仅读 */
        public boolean isWrite() {
            return write;
        }
    }

    /**
     * Buffer 内存屏障计划条目：本 Pass 对某 buffer 的访问前，需与该 buffer 的
     * 先前访问（上一依赖边另一端 Pass）同步。Buffer 无布局概念，条目只记
     * 访问方向（读写），同步域由执行器按两端 Pass 类型（compute/graphics）推导。
     */
    public static final class BufferBarrier {

        private final Buffer buffer;
        private final boolean write;

        BufferBarrier(Buffer buffer, boolean write) {
            this.buffer = buffer;
            this.write = write;
        }

        public Buffer buffer() {
            return buffer;
        }

        /** true = 本 Pass 写（storage buffer 写）；false = 仅读 */
        public boolean isWrite() {
            return write;
        }
    }

    /** 依赖边（声明索引） */
    public static final class Edge {

        private final int from;
        private final int to;

        Edge(int from, int to) {
            this.from = from;
            this.to = to;
        }

        public int from() {
            return from;
        }

        public int to() {
            return to;
        }
    }
}
