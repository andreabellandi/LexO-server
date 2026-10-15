package it.cnr.ilc.lexo.manager.cpp;

import it.cnr.ilc.lexo.manager.text.CollocationStatisticsService.Metric;
import java.util.EnumMap;
import java.util.Map;
import org.eclipse.rdf4j.model.IRI;

/** One authoritative mapping from the eighteen Lucene metrics to CollScore. */
public final class CppMeasureRegistry {
    private static final Map<Metric, String[]> MAP = new EnumMap<Metric, String[]>(Metric.class);
    static {
        put(Metric.RAW_FREQUENCY,"CooccurrenceFrequency","cooccurrenceFrequency");
        put(Metric.RELATIVE_FREQUENCY,"RelativeFrequency","relativeFrequency");
        put(Metric.PMI,"PMI","pmi"); put(Metric.PMI2,"PMI2","pmi2"); put(Metric.PMI3,"PMI3","pmi3");
        put(Metric.NPMI,"NPMI","npmi"); put(Metric.PMI_LOG_FREQ,"PMILogFreq","pmiLogFreq");
        put(Metric.DICE,"Dice","dice"); put(Metric.LOG_DICE,"LogDice","logDice");
        put(Metric.MIN_SENSITIVITY,"MinimumSensitivity","minimumSensitivity");
        put(Metric.T_SCORE,"TScore","tScore"); put(Metric.Z_SCORE,"ZScore","zScore");
        put(Metric.LOG_LIKELIHOOD,"LogLikelihood","logLikelihood"); put(Metric.CHI_SQUARE,"ChiSquared","chi2");
        put(Metric.SUPPORT,"Support","support"); put(Metric.CONFIDENCE,"Confidence","confidence");
        put(Metric.LIFT,"Lift","lift"); put(Metric.CONVICTION,"Conviction","conviction");
    }
    private static void put(Metric metric, String individual, String property) { MAP.put(metric, new String[]{individual,property}); }
    public static IRI individual(String name) {
        if ("FREQUENCY".equals(name)) { name = "RAW_FREQUENCY"; }
        try { return CppVocabulary.score(MAP.get(Metric.valueOf(name))[0]); }
        catch (RuntimeException e) { throw CppException.invalid("Unknown measure"); }
    }
    public static IRI property(Metric metric) { return CppVocabulary.score(MAP.get(metric)[1]); }
    public static IRI filter(String text) {
        for (Metric metric : Metric.values()) {
            if (individual(metric.name()).stringValue().equals(text)) { return individual(metric.name()); }
        }
        return individual(text);
    }
    private CppMeasureRegistry() { }
}
