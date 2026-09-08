package it.cnr.ilc.lexo.service.data.attestation.output;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.annotations.ApiModel;
import java.util.ArrayList;
import java.util.List;

/** A JSON-LD graph of Web Annotations, optionally with native LexO extensions. */
@ApiModel(description = "JSON-LD document containing W3C Web Annotations and optional LexO JSON literals")
public class WebAnnotationDocument {
    @JsonProperty("@context")
    public List<Object> context = new ArrayList<Object>();
    @JsonProperty("@graph")
    public List<ObjectNode> graph = new ArrayList<ObjectNode>();
}
