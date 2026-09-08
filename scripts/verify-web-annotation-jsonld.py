#!/usr/bin/env python3
"""Verify the Java-generated export using RDFLib's JSON-LD 1.1 processor.

Run the AttestationWebAnnotationExporterTest first. Requires rdflib>=6.
The fixture context is the subset of the W3C context used by this exporter;
it is injected locally so this check never retrieves remote contexts.
"""
import json
from pathlib import Path

from rdflib import Graph, Literal, Namespace, RDF, URIRef

ROOT = Path(__file__).resolve().parents[1]
document = json.loads((ROOT / "target/web-annotation-conformance.json").read_text())
pinned = json.loads((ROOT / "src/test/resources/web-annotation-context.json").read_text())
assert document["@context"][0] == "http://www.w3.org/ns/anno.jsonld"
document["@context"][0] = pinned["@context"]
graph = Graph().parse(data=json.dumps(document), format="json-ld")
oa = Namespace("http://www.w3.org/ns/oa#")
extension = Namespace("https://lexo.ilc.cnr.it/vocabulary/web-annotation#")
for annotation in document["@graph"]:
    subject = URIRef(annotation["id"])
    assert (subject, RDF.type, oa.Annotation) in graph
    assert (subject, oa.hasBody, URIRef(annotation["body"]["id"])) in graph
    target = graph.value(subject, oa.hasTarget)
    assert (target, RDF.type, oa.SpecificResource) in graph
    assert (target, oa.hasSource, URIRef(annotation["target"]["source"])) in graph
    selectors = list(graph.objects(target, oa.hasSelector))
    assert len(selectors) == 3
    quote = next(node for node in selectors if (node, RDF.type, oa.TextQuoteSelector) in graph)
    assert graph.value(quote, oa.exact) == Literal("a")
    for key, predicate in [("metadata", extension.metadata), ("lexoProvenance", extension.provenance)]:
        values = list(graph.objects(subject, predicate))
        assert len(values) == 1
        value = values[0]
        assert isinstance(value, Literal) and value.datatype == RDF.JSON
        assert json.loads(str(value)) == annotation[key]
    assert annotation["lexoProvenance"]["creator"] == "imported"
    assert annotation["lexoProvenance"]["lastUpdate"] is None
    assert annotation["metadata"]["http://www.w3.org/2004/02/skos/core#note"][0]["language"] == "it"
    assert annotation["metadata"]["https://example.org/future"][0]["type"] == "iri"
print("JSON-LD 1.1 RDF verification passed: WA relations and both lossless rdf:JSON extensions.")
