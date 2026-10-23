package it.cnr.ilc.lexo.service.data.text.output;

import it.cnr.ilc.lexo.service.data.Data;
import java.util.ArrayList;
import java.util.List;

public class UnfiledTexts implements Data {
    public int total;
    public List<FolderTextItem> texts = new ArrayList<FolderTextItem>();
}
