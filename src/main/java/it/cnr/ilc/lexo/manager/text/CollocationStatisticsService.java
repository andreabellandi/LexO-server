package it.cnr.ilc.lexo.manager.text;

import it.cnr.ilc.lexo.service.data.text.output.MetricValue;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure, extensible collocation formulas with explicit undefined values. */
public final class CollocationStatisticsService {

    public enum Metric {
        RAW_FREQUENCY, RELATIVE_FREQUENCY,
        PMI, PMI2, PMI3, NPMI, PMI_LOG_FREQ,
        DICE, LOG_DICE, MIN_SENSITIVITY,
        T_SCORE, Z_SCORE, LOG_LIKELIHOOD, CHI_SQUARE,
        SUPPORT, CONFIDENCE, LIFT, CONVICTION
    }

    public static final List<Metric> ALL = Arrays.asList(Metric.values());

    public Map<String, MetricValue> calculate(Counts counts, List<String> requested) {
        List<Metric> metrics = parse(requested);
        Map<String, MetricValue> result = new LinkedHashMap<String, MetricValue>();
        for (Metric metric : metrics) {
            result.put(metric.name(), calculate(metric, counts));
        }
        return result;
    }

    public MetricValue calculate(Metric metric, Counts c) {
        if (c == null || c.n < 0 || c.fx < 0 || c.fy < 0 || c.fxy < 0
                || c.pairN < 0 || c.pairFx < 0 || c.pairFy < 0
                || c.pairFxy < 0) {
            return undefined("invalid negative counts", basis(metric));
        }
        switch (metric) {
            case RAW_FREQUENCY:
                return value(c.fxy, "TOKEN_SPACE");
            case RELATIVE_FREQUENCY:
                return ratio(c.fxy, c.fx, "fx is zero", "TOKEN_SPACE");
            case PMI:
                return pmi(c, 1);
            case PMI2:
                return pmi(c, 2);
            case PMI3:
                return pmi(c, 3);
            case NPMI:
                MetricValue pmi = pmi(c, 1);
                if (!pmi.defined || c.n == 0 || c.fxy == 0 || c.fxy == c.n) {
                    return undefined("NPMI requires 0 < fxy < N", "TOKEN_SPACE");
                }
                return finite(pmi.value.doubleValue()
                        / -log2((double) c.fxy / c.n), "TOKEN_SPACE");
            case PMI_LOG_FREQ:
                MetricValue base = pmi(c, 1);
                return !base.defined ? base : finite(base.value.doubleValue()
                        * log2(c.fxy + 1.0d), "TOKEN_SPACE");
            case DICE:
                return ratio(2.0d * c.fxy, c.fx + c.fy,
                        "fx + fy is zero", "TOKEN_SPACE");
            case LOG_DICE:
                MetricValue dice = ratio(2.0d * c.fxy, c.fx + c.fy,
                        "fx + fy is zero", "TOKEN_SPACE");
                return !dice.defined || dice.value.doubleValue() <= 0.0d
                        ? undefined("Dice must be positive", "TOKEN_SPACE")
                        : finite(14.0d + log2(dice.value.doubleValue()), "TOKEN_SPACE");
            case MIN_SENSITIVITY:
                if (c.fx == 0 || c.fy == 0) {
                    return undefined("fx and fy must be positive", "TOKEN_SPACE");
                }
                return value(Math.min((double) c.fxy / c.fx,
                        (double) c.fxy / c.fy), "TOKEN_SPACE");
            case T_SCORE:
                if (c.fxy == 0 || c.n == 0) {
                    return undefined("t-score requires fxy and N", "TOKEN_SPACE");
                }
                return finite((c.fxy - expected(c)) / Math.sqrt(c.fxy),
                        "TOKEN_SPACE");
            case Z_SCORE:
                double expected = expected(c);
                return expected <= 0.0d
                        ? undefined("expected frequency is zero", "TOKEN_SPACE")
                        : finite((c.fxy - expected) / Math.sqrt(expected), "TOKEN_SPACE");
            case LOG_LIKELIHOOD:
                return contingency(c, true);
            case CHI_SQUARE:
                return contingency(c, false);
            case SUPPORT:
                return ratio(c.pairFxy, c.pairN,
                        "pairN is zero", "PAIR_SPACE");
            case CONFIDENCE:
                return ratio(c.pairFxy, c.pairFx,
                        "pairFx is zero", "PAIR_SPACE");
            case LIFT:
                if (c.pairFx == 0 || c.pairFy == 0 || c.pairN == 0) {
                    return undefined("pair marginals must be positive", "PAIR_SPACE");
                }
                return finite(((double) c.pairFxy / c.pairFx)
                        / ((double) c.pairFy / c.pairN), "PAIR_SPACE");
            case CONVICTION:
                if (c.pairFx == 0 || c.pairN == 0) {
                    return undefined("pairFx and pairN must be positive", "PAIR_SPACE");
                }
                double confidence = (double) c.pairFxy / c.pairFx;
                if (confidence == 1.0d) {
                    return undefined("conviction is unbounded when confidence is one",
                            "PAIR_SPACE");
                }
                return finite((1.0d - (double) c.pairFy / c.pairN)
                        / (1.0d - confidence), "PAIR_SPACE");
            default:
                return undefined("unsupported metric", basis(metric));
        }
    }

    private MetricValue pmi(Counts c, int power) {
        if (c.n == 0 || c.fx == 0 || c.fy == 0 || c.fxy == 0) {
            return undefined("PMI requires positive N, fx, fy and fxy", "TOKEN_SPACE");
        }
        double numerator = Math.pow(c.fxy, power) * c.n;
        return finite(log2(numerator / ((double) c.fx * c.fy)), "TOKEN_SPACE");
    }

    private MetricValue contingency(Counts c, boolean likelihood) {
        double o11 = c.pairFxy;
        double o12 = c.pairFx - c.pairFxy;
        double o21 = c.pairFy - c.pairFxy;
        double o22 = c.pairN - o11 - o12 - o21;
        if (c.pairN <= 0 || o11 < 0 || o12 < 0 || o21 < 0 || o22 < 0) {
            return undefined("invalid PAIR_SPACE contingency table", "PAIR_SPACE");
        }
        double row1 = o11 + o12;
        double row2 = o21 + o22;
        double col1 = o11 + o21;
        double col2 = o12 + o22;
        double[] observed = {o11, o12, o21, o22};
        double[] expected = {
            row1 * col1 / c.pairN, row1 * col2 / c.pairN,
            row2 * col1 / c.pairN, row2 * col2 / c.pairN};
        double statistic = 0.0d;
        for (int index = 0; index < observed.length; index++) {
            if (expected[index] == 0.0d) {
                if (observed[index] != 0.0d) {
                    return undefined("zero expected contingency cell", "PAIR_SPACE");
                }
                continue;
            }
            if (likelihood) {
                if (observed[index] > 0.0d) {
                    statistic += observed[index]
                            * Math.log(observed[index] / expected[index]);
                }
            } else {
                double difference = observed[index] - expected[index];
                statistic += difference * difference / expected[index];
            }
        }
        return finite(likelihood ? 2.0d * statistic : statistic, "PAIR_SPACE");
    }

    private static double expected(Counts c) {
        return c.n == 0 ? 0.0d : (double) c.fx * c.fy / c.n;
    }

    private static MetricValue ratio(double numerator, double denominator,
                                     String reason, String basis) {
        return denominator == 0.0d ? undefined(reason, basis)
                : finite(numerator / denominator, basis);
    }

    private static MetricValue finite(double number, String basis) {
        return Double.isFinite(number) ? value(number, basis)
                : undefined("result is not finite", basis);
    }

    private static MetricValue value(double number, String basis) {
        return MetricValue.defined(number, basis);
    }

    private static MetricValue undefined(String reason, String basis) {
        return MetricValue.undefined(reason, basis);
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0d);
    }

    private static String basis(Metric metric) {
        switch (metric) {
            case LOG_LIKELIHOOD:
            case CHI_SQUARE:
            case SUPPORT:
            case CONFIDENCE:
            case LIFT:
            case CONVICTION:
                return "PAIR_SPACE";
            default:
                return "TOKEN_SPACE";
        }
    }

    private static List<Metric> parse(List<String> requested) {
        if (requested == null || requested.isEmpty()
                || requested.contains("ALL")) {
            return ALL;
        }
        java.util.ArrayList<Metric> result = new java.util.ArrayList<Metric>();
        for (String value : requested) {
            try {
                Metric metric = Metric.valueOf(value.trim().toUpperCase(Locale.ROOT));
                if (!result.contains(metric)) {
                    result.add(metric);
                }
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("INVALID_METRIC: " + value);
            }
        }
        return result;
    }

    public static final class Counts {
        public long n;
        public long fx;
        public long fy;
        public long fxy;
        public long pairN;
        public long pairFx;
        public long pairFy;
        public long pairFxy;
    }
}
