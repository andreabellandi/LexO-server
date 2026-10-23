package it.cnr.ilc.lexo.service.data.text.output;

import java.util.ArrayList;
import java.util.List;

public class FolderNode extends FolderRecord {
    public List<FolderNode> children = new ArrayList<FolderNode>();
    public List<FolderTextItem> texts = new ArrayList<FolderTextItem>();

    public FolderNode() {
    }

    public FolderNode(FolderRecord source) {
        super(source);
    }
}
