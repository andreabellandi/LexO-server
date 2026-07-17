package it.cnr.ilc.lexo.service.helper;

import it.cnr.ilc.lexo.LexOFilter;
import it.cnr.ilc.lexo.service.data.Info;
import java.util.Date;

/**
 *
 * @author andreabellandi
 */
public class InfoHelper extends Helper<Info> {

    @Override
    public Class<Info> getDataClass() {
        return Info.class;
    }

    public Info newData() {
        Info data = new Info();
        data.setName(LexOFilter.CONTEXT);
        data.setVersion(LexOFilter.VERSION);
        data.setServerTime(new Date());
        data.setJavaVersion(System.getProperty("java.version"));
        return data;
    }

}
