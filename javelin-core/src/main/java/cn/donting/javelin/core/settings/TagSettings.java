package cn.donting.javelin.core.settings;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.inspector.InsField;
import cn.donting.javelin.core.inspector.InsTagList;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

import java.util.ArrayList;

/**
 * 标签设置（项目设置「标签」页根）：节点 Tag 注册表名单。
 *
 * <p>由 {@link JavelinSetting#getTagSettings()} 持有；设置窗只允许新增 / 删除（改名等于另一个
 * 标签），改动经 {@code LayerAndTagManger} 即时同步注册表并把已删除标签从场景节点上摘除。</p>
 **/
@Reflective
@Setting(label = "projectSettings.category.tags")
public class TagSettings implements Serializable {

    /** 标签名清单（顺序即面板行序；面板只增删、不给改名） */
    @InsField(label = "projectSettings.tags")
    @InsTagList
    @SettingHint("projectSettings.tagsHint")
    private ArrayList<String> tags = new ArrayList<>();

    public ArrayList<String> getTags() {
        return tags;
    }

    public void setTags(ArrayList<String> tags) {
        if (tags == null) {
            throw new IllegalArgumentException("tags must not be null");
        }
        this.tags = tags;
    }

    public void add(String tag) {
        if (tags.contains(tag)) {
            return;
        }
        tags.add(tag);
    }

    public boolean remove(String tag) {
        return tags.remove(tag);
    }

    @Override
    public void write(OutputCapsule output) {
        output.writeArrayString("tags", tags);
    }

    @Override
    public void read(InputCapsule input) {
        ArrayList<String> loaded = input.readArrayString("tags");
        if (loaded != null) {
            tags = loaded;
        }
    }
}
