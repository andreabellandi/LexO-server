package it.cnr.ilc.lexo.service.data.lexint.output;

import java.util.ArrayList;
import java.util.List;

public final class LexIntPage<T> {
    public long total;
    public int limit;
    public int offset;
    public List<T> items = new ArrayList<T>();
}
