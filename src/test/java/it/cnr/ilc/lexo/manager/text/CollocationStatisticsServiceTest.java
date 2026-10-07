package it.cnr.ilc.lexo.manager.text;

import static org.assertj.core.api.Assertions.assertThat;

import it.cnr.ilc.lexo.manager.text.CollocationStatisticsService.Counts;
import it.cnr.ilc.lexo.manager.text.CollocationStatisticsService.Metric;
import it.cnr.ilc.lexo.service.data.text.output.MetricValue;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CollocationStatisticsServiceTest {

    private final CollocationStatisticsService statistics =
            new CollocationStatisticsService();

    @Test
    @DisplayName("Every requested collocation formula uses its documented sample space")
    void computesEveryMetricForKnownCounts() {
        Counts counts = counts();
        Map<String, MetricValue> result = statistics.calculate(counts, null);

        assertThat(result).hasSize(Metric.values().length);
        assertThat(result.values()).allMatch(metric -> metric.defined
                && metric.value != null && Double.isFinite(metric.value.doubleValue()));
        assertValue(result, "RAW_FREQUENCY", 20.0d);
        assertValue(result, "RELATIVE_FREQUENCY", 0.2d);
        assertValue(result, "PMI", log2(4.0d));
        assertValue(result, "PMI2", log2(80.0d));
        assertValue(result, "PMI3", log2(1600.0d));
        assertValue(result, "DICE", 40.0d / 150.0d);
        assertValue(result, "MIN_SENSITIVITY", 0.2d);
        assertValue(result, "SUPPORT", 40.0d / 5000.0d);
        assertValue(result, "CONFIDENCE", 40.0d / 500.0d);
        assertValue(result, "LIFT", 1.6d);
        assertValue(result, "CONVICTION", 0.95d / 0.92d);
        assertThat(result.get("LOG_LIKELIHOOD").basis).isEqualTo("PAIR_SPACE");
        assertThat(result.get("CHI_SQUARE").basis).isEqualTo("PAIR_SPACE");
        assertThat(result.get("PMI").basis).isEqualTo("TOKEN_SPACE");
    }

    @Test
    @DisplayName("Undefined statistics never expose NaN or infinity")
    void representsZeroDenominatorsExplicitly() {
        Counts empty = new Counts();
        for (Metric metric : Metric.values()) {
            MetricValue value = statistics.calculate(metric, empty);
            if (value.defined) {
                assertThat(value.value).isNotNull().isFinite();
            } else {
                assertThat(value.value).isNull();
                assertThat(value.reason).isNotBlank();
            }
        }
        assertThat(statistics.calculate(Metric.PMI, empty).defined).isFalse();
        assertThat(statistics.calculate(Metric.CONVICTION, empty).defined).isFalse();
    }

    private static Counts counts() {
        Counts result = new Counts();
        result.n = 1000;
        result.fx = 100;
        result.fy = 50;
        result.fxy = 20;
        result.pairN = 5000;
        result.pairFx = 500;
        result.pairFy = 250;
        result.pairFxy = 40;
        return result;
    }

    private static void assertValue(Map<String, MetricValue> values,
                                    String metric, double expected) {
        assertThat(values.get(metric).value).isCloseTo(expected,
                org.assertj.core.data.Offset.offset(1.0e-12));
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0d);
    }
}
