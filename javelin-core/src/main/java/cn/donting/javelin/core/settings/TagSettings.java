package cn.donting.javelin.core.settings;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

import java.util.ArrayList;

@Reflective
public class TagSettings implements Serializable {
    private ArrayList<String> tags = new ArrayList<>();

    public ArrayList<String> getTags() {
        return tags;
    }

    public void add(String tag){
        if (tags.contains(tag)) {
            return;
        }
        tags.add(tag);
    }
    public boolean remove(String tag){
        return tags.remove(tag);
    }
    @Override
    public void write(OutputCapsule output) {
        output.writeArrayString("tags",tags);
    }

    @Override
    public void read(InputCapsule input) {
        tags=input.readArrayString("tags");
    }
}
