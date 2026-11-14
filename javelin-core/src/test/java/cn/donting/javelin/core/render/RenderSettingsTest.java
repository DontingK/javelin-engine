package cn.donting.javelin.core.render;

import cn.donting.javelin.core.settings.JavelinSetting;
import cn.donting.javelin.core.settings.ShadowQuality;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渲染设置门面单测（Engine_Rendering_Design §11）：绑定后扁平 setter 写回 setting 活实例；
 * 未绑定调用抛异常（不静默）；解绑后失效。后处理/管线引用可空（门面 null 守卫），
 * 故无需 GPU 即可测门面路由。
 */
public class RenderSettingsTest {

    @AfterEach
    public void tearDown() {
        RenderSettings.unbind();   // 静态门面：跨用例清态
    }

    /** 阴影扁平 setter 写 live 实例（即 setting.getShadowSettings()），读回一致 */
    @Test
    public void shadowFlatSettersWriteLiveInstance() {
        JavelinSetting setting = new JavelinSetting();
        RenderSettings.bind(setting, null, null);

        assertSame(setting.getShadowSettings(), RenderSettings.shadow(),
                "shadow() 应返回 setting 持有的 live 实例");

        RenderSettings.setShadowQuality(ShadowQuality.SOFT_HIGH);
        RenderSettings.setShadowStrength(0.4f);
        RenderSettings.setShadowCascadeCount(4);
        RenderSettings.setShadowDistance(150f);

        assertEquals(ShadowQuality.SOFT_HIGH, setting.getShadowSettings().getQuality());
        assertEquals(0.4f, setting.getShadowSettings().getStrength(), 1e-6f);
        assertEquals(4, setting.getShadowSettings().getCascadeCount());
        assertEquals(150f, setting.getShadowSettings().getShadowDistance(), 1e-6f);
        assertEquals(0.4f, RenderSettings.getShadowStrength(), 1e-6f);
    }

    /** 后处理参数扁平 setter 写回 setting 嵌套对象（无组合根时仅落 setting） */
    @Test
    public void postProcessSettersWriteSetting() {
        JavelinSetting setting = new JavelinSetting();
        RenderSettings.bind(setting, null, null);

        RenderSettings.setBloom(1.2f, 0.3f, 0.7f);
        RenderSettings.setTonemapKnee(0.9f);
        RenderSettings.setSsao(0.8f, 0.03f, 1.5f);

        assertEquals(1.2f, setting.getPostProcessSettings().getBloomThreshold(), 1e-6f);
        assertEquals(0.3f, setting.getPostProcessSettings().getBloomKnee(), 1e-6f);
        assertEquals(0.7f, setting.getPostProcessSettings().getBloomIntensity(), 1e-6f);
        assertEquals(0.9f, setting.getPostProcessSettings().getTonemapKnee(), 1e-6f);
        assertEquals(0.8f, setting.getPostProcessSettings().getSsao().getRadius(), 1e-6f);
        assertEquals(1.5f, setting.getPostProcessSettings().getSsao().getIntensity(), 1e-6f);
    }

    /** 背景色写 setting（无管线/组合根时不抛） */
    @Test
    public void backgroundWritesSetting() {
        JavelinSetting setting = new JavelinSetting();
        RenderSettings.bind(setting, null, null);
        RenderSettings.setBackgroundColor(0.1f, 0.2f, 0.3f, 1.0f);
        assertEquals(0.1f, setting.getBackgroundColor().r, 1e-6f);
        assertEquals(0.3f, setting.getBackgroundColor().b, 1e-6f);
    }

    /** 未绑定即调用抛 IllegalStateException（不静默）；解绑后同样失效 */
    @Test
    public void unboundCallsThrow() {
        RenderSettings.unbind();
        assertThrows(IllegalStateException.class, RenderSettings::getShadowStrength);
        assertThrows(IllegalStateException.class, () -> RenderSettings.setBloom(1f, 0.5f, 1f));

        RenderSettings.bind(new JavelinSetting(), null, null);
        assertTrue(RenderSettings.getShadowStrength() > 0f);
        RenderSettings.unbind();
        assertThrows(IllegalStateException.class, RenderSettings::getShadowStrength);
    }
}
