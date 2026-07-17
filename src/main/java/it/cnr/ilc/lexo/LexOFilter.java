package it.cnr.ilc.lexo;

import it.cnr.ilc.lexo.manager.converter.adapter.OntoLexToTBXConverterAdapter;
import it.cnr.ilc.lexo.bootstrap.GraphDbBootstrap;
import it.cnr.ilc.lexo.sparql.SparqlSelectData;
import it.cnr.ilc.lexo.sparql.SparqlVariable;
import it.cnr.ilc.lexo.util.ConverterRegistry;
import it.cnr.ilc.lexo.util.RDFQueryUtil;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import org.apache.log4j.DailyRollingFileAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.TupleQueryResult;

/**
 *
 * @author andreabellandi
 */
@WebFilter(urlPatterns = {"/faces/*", "/service/*", "/servlet/*"})
public class LexOFilter implements Filter {

    static final Logger logger = Logger.getLogger(LexOFilter.class.getName());
    public static String CONTEXT;
    public static String VERSION;

    public static String fileSystemPath;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        fileSystemPath = filterConfig.getServletContext().getRealPath("/");
        CONTEXT = filterConfig.getServletContext().getContextPath().substring(1);
        VERSION = LexOProperties.getProperty("application.version");
        File logFile = new File(filterConfig.getServletContext().getRealPath("/"));
        logFile = new File(logFile.getParentFile().getParentFile(), "logs/" + CONTEXT + ".log");
        PatternLayout layout = new PatternLayout();
        String conversionPattern = "%d %p %m\n";
        layout.setConversionPattern(conversionPattern);
        DailyRollingFileAppender rollingAppender = new DailyRollingFileAppender();
        rollingAppender.setFile(logFile.getAbsolutePath());
        rollingAppender.setDatePattern("'.'yyyy-MM-dd");
        rollingAppender.setLayout(layout);
        rollingAppender.activateOptions();
        ConverterRegistry.get().register(new OntoLexToTBXConverterAdapter());
        Logger logger = Logger.getLogger(CONTEXT);
        logger.setLevel(Level.INFO);
        logger.addAppender(rollingAppender);
        logger.info(CONTEXT + " start");
        try {
            GraphDbBootstrap.initialize();
            setResourceModel();
        } catch (RuntimeException ex) {
            logger.error("GraphDB bootstrap failed", ex);
            throw new ServletException("Unable to initialize GraphDB repositories", ex);
        }
    }

    private void setResourceModel() {
        ArrayList<String> model = new ArrayList();
        try ( TupleQueryResult result = RDFQueryUtil.evaluateTQuery(SparqlSelectData.GET_RESOURCE_MODEL)) {
            while (result.hasNext()) {
                BindingSet bs = result.next();
                model.add(bs.getBinding(SparqlVariable.VALUE).getValue().stringValue());
            }
            if (model.size() == 1) {
                LexOProperties.setProperty("resourceModel", model.get(0));
            }
            LexOProperties.load();
        } catch (QueryEvaluationException qee) {
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        logger.debug("doFilter() Request URL: " + httpRequest.getRequestURL().toString());
        chain.doFilter(request, response);
        logger.debug("End of doFilter()");
    }

    @Override
    public void destroy() {
        // Logger.getLogger(CONTEXT).info(CONTEXT + " stop");
        logger.info("destroy() " + CONTEXT + " stop");
        GraphDbUtil.shutDown();
    }

}
