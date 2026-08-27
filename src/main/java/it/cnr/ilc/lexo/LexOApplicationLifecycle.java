package it.cnr.ilc.lexo;

import it.cnr.ilc.lexo.bootstrap.GraphDbBootstrap;
import it.cnr.ilc.lexo.manager.converter.adapter.OntoLexToTBXConverterAdapter;
import it.cnr.ilc.lexo.sparql.SparqlSelectData;
import it.cnr.ilc.lexo.sparql.SparqlVariable;
import it.cnr.ilc.lexo.util.ConverterRegistry;
import it.cnr.ilc.lexo.util.RDFQueryUtil;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns application startup and shutdown independently from HTTP requests. */
@WebListener
public final class LexOApplicationLifecycle implements ServletContextListener {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(LexOApplicationLifecycle.class);

    @Override
    public void contextInitialized(ServletContextEvent event) {
        ServletContext context = event.getServletContext();
        LexOFilter.fileSystemPath = context.getRealPath("/");
        String contextPath = context.getContextPath();
        LexOFilter.CONTEXT = contextPath == null || contextPath.isEmpty()
                ? "ROOT" : contextPath.substring(1);
        LexOFilter.VERSION = LexOProperties.getProperty("application.version");
        ConverterRegistry.get().register(new OntoLexToTBXConverterAdapter());
        LOGGER.info("LexO-server starting context={} version={}",
                LexOFilter.CONTEXT, LexOFilter.VERSION);
        try {
            GraphDbBootstrap.initialize();
            setResourceModel();
            LOGGER.info("LexO-server started context={}", LexOFilter.CONTEXT);
        } catch (RuntimeException e) {
            LOGGER.error("LexO-server startup failed context={}",
                    LexOFilter.CONTEXT, e);
            throw e;
        }
    }

    private void setResourceModel() {
        List<String> model = new ArrayList<String>();
        try (TupleQueryResult result =
                     RDFQueryUtil.evaluateTQuery(SparqlSelectData.GET_RESOURCE_MODEL)) {
            if (result == null) {
                LOGGER.warn("Resource model query returned no result handle");
                return;
            }
            while (result.hasNext()) {
                BindingSet bindings = result.next();
                if (bindings.getBinding(SparqlVariable.VALUE) != null) {
                    model.add(bindings.getBinding(SparqlVariable.VALUE)
                            .getValue().stringValue());
                }
            }
            if (model.size() == 1) {
                LexOProperties.setProperty("resourceModel", model.get(0));
            }
            LexOProperties.load();
        } catch (QueryEvaluationException e) {
            LOGGER.warn("Unable to resolve the configured resource model", e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        LOGGER.info("LexO-server stopping context={}", LexOFilter.CONTEXT);
        GraphDbUtil.shutDown();
        LOGGER.info("LexO-server stopped context={}", LexOFilter.CONTEXT);
    }
}
