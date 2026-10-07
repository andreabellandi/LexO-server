package it.cnr.ilc.lexo.manager.text;

import it.cnr.ilc.lexo.service.data.text.input.CollocateExtractionRequest;
import it.cnr.ilc.lexo.service.data.text.input.CooccurrenceRequest;
import it.cnr.ilc.lexo.service.data.text.input.FrequencyRequest;
import it.cnr.ilc.lexo.service.data.text.input.FullTextSearchRequest;
import it.cnr.ilc.lexo.service.data.text.input.KwicResizeRequest;
import it.cnr.ilc.lexo.service.data.text.output.CollocateExtractionResult;
import it.cnr.ilc.lexo.service.data.text.output.CooccurrenceResult;
import it.cnr.ilc.lexo.service.data.text.output.FrequencyResult;
import it.cnr.ilc.lexo.service.data.text.output.FullTextSearchResult;
import it.cnr.ilc.lexo.service.data.text.output.TextOccurrence;
import java.io.IOException;
import java.util.List;

public interface CorpusSearchService {
    FullTextSearchResult fullText(FullTextSearchRequest request) throws IOException;
    List<TextOccurrence> resize(KwicResizeRequest request) throws IOException;
    CooccurrenceResult cooccurrences(CooccurrenceRequest request) throws IOException;
    FrequencyResult frequency(FrequencyRequest request) throws IOException;
    CollocateExtractionResult extract(CollocateExtractionRequest request) throws IOException;
}
