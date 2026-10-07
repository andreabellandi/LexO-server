package it.cnr.ilc.lexo.service.data.text.output;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class MetricValue {
    public Double value;
    public boolean defined;
    public String reason;
    public String basis;

    public static MetricValue defined(double value, String basis) {
        MetricValue result = new MetricValue();
        result.value = Double.valueOf(value);
        result.defined = true;
        result.basis = basis;
        return result;
    }

    public static MetricValue undefined(String reason, String basis) {
        MetricValue result = new MetricValue();
        result.defined = false;
        result.reason = reason;
        result.basis = basis;
        return result;
    }
}
