package com.javelin.editor.jcef.method;

import com.javelin.core.obj.ObjectCache;
import com.javelin.core.obj.ObjectId;
import com.javelin.core.scene.Node;
import com.javelin.editor.JavelinEditor;
import com.javelin.editor.exception.TipException;
import com.javelin.editor.inspector.InspectorReflector;
import com.javelin.editor.jcef.bridge.BridgeMethod;
import com.javelin.editor.jcef.domain.par.IdPar;
import com.javelin.editor.jcef.domain.par.InspectorValuePar;
import com.javelin.editor.jcef.domain.vo.NodeInspectorVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Inspector 桥方法：/inspector/info（选中节点可检视字段快照）、
 * /inspector/setValue（字段值回写）。
 *
 * <p>两端经 JavelinEngine.submitTask 提交引擎主线程帧边界执行（反射收集/回写与
 * mainLoop 的场景访问无竞争），CompletableFuture 回传结果、await 超时兜底——
 * 「未就绪」走 VO 字段/日志不走异常（桥层异常弹原生对话框，同 SceneMethod 口径）。
 * v0.1 字段面 = 节点头 + Transform（组件白名单见 InspectorReflector）。</p>
 *
 * @author javelin
 * @create 2026/10/8
 **/
@BridgeMethod("/inspector")
public class InspectorMethod {

    /** 拉取/回写等待上限（毫秒）：对齐 SceneMethod（帧边界正常 ~16ms，2 秒视作主循环异常） */
    private static final long AWAIT_TIMEOUT_MILLIS = 2000;

    private static final Logger LOGGER = LoggerFactory.getLogger(InspectorMethod.class);

    /**
     * 拉取节点 Inspector 快照（节点头 + 组件字段分组）。
     * 节点不存在/已被 GC → ready=false（前端清面板或重试兜底）。
     */
    @BridgeMethod("/info")
    public NodeInspectorVo info(IdPar idPar) {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null || idPar == null) {
            return NodeInspectorVo.notReady();
        }
        CompletableFuture<NodeInspectorVo> future = new CompletableFuture<>();
        editor.submitTask(() -> future.complete(collectInfo(idPar.id)));
        NodeInspectorVo vo = await(future);
        return vo != null ? vo : NodeInspectorVo.notReady();
    }

    /**
     * 字段值回写：按 valueType 解析后经 setter 写入 + 场景置脏（前端乐观更新、
     * fire-and-forget 调用）。Transform 回写由 TransformWatchAppState 帧级 diff
     * 自动检出并推 scene.nodeTransformChanged（面板防抖重拉）；节点头 name 改名
     * 由 SceneWatchAppState 检出推 scene.nodeRenamed/scene.changed。
     */
    @BridgeMethod("/setValue")
    public void setValue(InspectorValuePar valuePar) {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null || valuePar == null) {
            LOGGER.warn("inspector/setValue 忽略：编辑器未就绪或参数缺失");
            return;
        }
        CompletableFuture<Throwable> future = new CompletableFuture<>();
        editor.submitTask(() -> {
            try {
                applyValue(editor, valuePar);
                future.complete(null);
            } catch (Throwable t) {
                future.complete(t);
            }
        });
        Throwable failure = await(future);
        if (failure == null) {
            return;
        }
        if (failure instanceof TipException) {
            throw (TipException) failure;
        }
        throw new TipException("Inspector 回写失败: " + failure.getMessage(), failure);
    }

    /** 引擎主线程执行：节点反查 + 反射收集（节点已回收 → 未就绪 VO） */
    private static NodeInspectorVo collectInfo(int nodeId) {
        Node node = ObjectCache.get(nodeId);
        if (node == null) {
            return NodeInspectorVo.notReady();
        }
        return InspectorReflector.collect(node);
    }

    /** 引擎主线程执行：回写 + 置脏（对象已回收 → 静默忽略记日志，常态竞态不弹窗） */
    private static void applyValue(JavelinEditor editor, InspectorValuePar par) {
        ObjectId host = ObjectCache.get(par.objId);
        if (host == null) {
            LOGGER.warn("inspector/setValue 忽略：对象已不存在（objId={}，可能已删除）", par.objId);
            return;
        }
        InspectorReflector.apply(host, par.fieldName, par.valueType, par.value);
        if (editor.getEditorScene() != null) {
            editor.getEditorScene().markDirty();
        }
    }

    /** 阻塞等待结果（桥线程专用；超时/中断记日志返回 null，由调用方转未就绪，不上抛） */
    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(AWAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOGGER.warn("inspector 桥方法等待引擎线程超时");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            // 任务内异常均已捕获转值正常完成，此分支仅兜底
            LOGGER.error("inspector 桥方法执行异常", e.getCause());
            return null;
        }
    }
}
