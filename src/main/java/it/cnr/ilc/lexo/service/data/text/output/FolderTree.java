package it.cnr.ilc.lexo.service.data.text.output;

import it.cnr.ilc.lexo.service.data.Data;
import java.util.ArrayList;
import java.util.List;

public class FolderTree implements Data {
    public List<FolderNode> folders = new ArrayList<FolderNode>();
}
