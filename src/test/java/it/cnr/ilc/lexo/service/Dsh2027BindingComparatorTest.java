package it.cnr.ilc.lexo.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class Dsh2027BindingComparatorTest {
    private static final Dsh2027BindingComparator.Term V1 =
            Dsh2027BindingComparator.Term.iri("urn:version:1");
    private static final Dsh2027BindingComparator.Term V2 =
            Dsh2027BindingComparator.Term.iri("urn:version:2");

    @Test
    void rejectsCq01SwappedAssociationsWithSameValuesAndCount() {
        Dsh2027BindingComparator.Row first = row("version", V1,
                "etymology", iri("urn:ety:ratio"));
        Dsh2027BindingComparator.Row second = row("version", V2,
                "etymology", iri("urn:ety:haraz"));
        Dsh2027BindingComparator.Expected expected = expected(first, second);
        Dsh2027BindingComparator.Comparison result = compare(expected,
                row("version", V1, "etymology", iri("urn:ety:haraz")),
                row("version", V2, "etymology", iri("urn:ety:ratio")));
        assertRejected(result, 2, 2);
    }

    @Test
    void rejectsCq04OperationObjectCrossWiring() {
        Dsh2027BindingComparator.Row deletion = row("operation", iri("urn:op:d"),
                "object", iri("urn:ety:ratio"));
        Dsh2027BindingComparator.Row insertion = row("operation", iri("urn:op:i"),
                "object", iri("urn:ety:haraz"));
        Dsh2027BindingComparator.Comparison result = compare(
                expected(deletion, insertion),
                row("operation", iri("urn:op:d"), "object", iri("urn:ety:haraz")),
                row("operation", iri("urn:op:i"), "object", iri("urn:ety:ratio")));
        assertRejected(result, 2, 2);
    }

    @Test
    void rejectsCq05TargetCandidateSwapAcrossSixRows() {
        Dsh2027BindingComparator.Row[] expected = new Dsh2027BindingComparator.Row[6];
        Dsh2027BindingComparator.Row[] observed = new Dsh2027BindingComparator.Row[6];
        int index = 0;
        for (String source : Arrays.asList("a", "b")) {
            for (int target = 1; target <= 3; target++) {
                expected[index] = row("source", iri("urn:source:" + source),
                        "target", iri("urn:target:" + target),
                        "object", iri("urn:candidate:" + target));
                int swapped = target == 1 ? 2 : target == 2 ? 1 : 3;
                observed[index] = row("source", iri("urn:source:" + source),
                        "target", iri("urn:target:" + target),
                        "object", iri("urn:candidate:" + swapped));
                index++;
            }
        }
        Dsh2027BindingComparator.Comparison result = compare(
                expected(expected), observed);
        assertRejected(result, 4, 4);
    }

    @Test
    void rejectsCq07DuplicateOrAlteredAuditRow() {
        Dsh2027BindingComparator.Row pass = row("verification", iri("urn:v:1"),
                "sourceVersion", V1, "targetVersion", V2,
                "outcome", iri("urn:outcome:pass"));
        Dsh2027BindingComparator.Row indeterminate = row("verification",
                iri("urn:v:2"), "sourceVersion", V1, "targetVersion", V2,
                "outcome", iri("urn:outcome:indeterminate"));
        Dsh2027BindingComparator.Expected expected = expected(pass, indeterminate);
        Dsh2027BindingComparator.Comparison duplicate = compare(expected,
                pass, indeterminate, pass);
        assertThat(duplicate.status).isEqualTo("INCORRECT");
        assertThat(duplicate.unexpectedRows).hasSize(1);

        Dsh2027BindingComparator.Comparison altered = compare(expected,
                pass, row("verification", iri("urn:v:2"),
                        "sourceVersion", V2, "targetVersion", V1,
                        "outcome", iri("urn:outcome:pass")));
        assertRejected(altered, 1, 1);
    }

    private static Dsh2027BindingComparator.Term iri(String value) {
        return Dsh2027BindingComparator.Term.iri(value);
    }

    private static Dsh2027BindingComparator.Row row(Object... values) {
        return Dsh2027BindingComparator.row(values);
    }

    private static Dsh2027BindingComparator.Expected expected(
            Dsh2027BindingComparator.Row... rows) {
        return expected(Arrays.asList(rows));
    }

    private static Dsh2027BindingComparator.Expected expected(
            java.util.List<Dsh2027BindingComparator.Row> rows) {
        return new Dsh2027BindingComparator.Expected(
                new java.util.ArrayList<String>(rows.get(0).bindings.keySet()),
                rows, "MULTISET");
    }

    private static Dsh2027BindingComparator.Comparison compare(
            Dsh2027BindingComparator.Expected expected,
            Dsh2027BindingComparator.Row... observed) {
        return Dsh2027BindingComparator.compare(expected,
                Arrays.asList(observed));
    }

    private static void assertRejected(
            Dsh2027BindingComparator.Comparison result, int missing,
            int unexpected) {
        assertThat(result.status).isEqualTo("INCORRECT");
        assertThat(result.missingRows).hasSize(missing);
        assertThat(result.unexpectedRows).hasSize(unexpected);
        assertThat(result.completeness()).isLessThan(1.0d);
        assertThat(result.precision()).isLessThan(1.0d);
    }
}
