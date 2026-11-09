package it.cnr.ilc.lexo.manager.wordnet;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/** RDF vocabulary constants used by the WordNet API profile. */
public final class WordnetVocabulary {

    public static final String WN_NS = "https://globalwordnet.github.io/schemas/wn#";
    public static final String ONTOLEX_NS = "http://www.w3.org/ns/lemon/ontolex#";
    public static final String LIME_NS = "http://www.w3.org/ns/lemon/lime#";
    public static final String VARTRANS_NS = "http://www.w3.org/ns/lemon/vartrans#";
    public static final String SYNSEM_NS = "http://www.w3.org/ns/lemon/synsem#";
    public static final String LEXO_NS = "https://lexo.ilc.cnr.it#";

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    public static final IRI LEXICON = iri(LIME_NS + "Lexicon");
    public static final IRI LANGUAGE = iri(LIME_NS + "language");
    public static final IRI ENTRY_LINK = iri(LIME_NS + "entry");
    public static final IRI ENTRY = iri(ONTOLEX_NS + "LexicalEntry");
    public static final IRI FORM = iri(ONTOLEX_NS + "Form");
    public static final IRI SENSE = iri(ONTOLEX_NS + "LexicalSense");
    public static final IRI SYNSET = iri(ONTOLEX_NS + "LexicalConcept");
    public static final IRI CANONICAL_FORM = iri(ONTOLEX_NS + "canonicalForm");
    public static final IRI OTHER_FORM = iri(ONTOLEX_NS + "otherForm");
    public static final IRI WRITTEN_REP = iri(ONTOLEX_NS + "writtenRep");
    public static final IRI SENSE_LINK = iri(ONTOLEX_NS + "sense");
    public static final IRI REFERENCE = iri(ONTOLEX_NS + "reference");
    public static final IRI PART_OF_SPEECH = iri(WN_NS + "partOfSpeech");
    public static final IRI INDEX = iri(WN_NS + "index");
    public static final IRI SCRIPT = iri(WN_NS + "script");
    public static final IRI N = iri(WN_NS + "n");
    public static final IRI ILI = iri(WN_NS + "ili");
    public static final IRI LEXFILE = iri(WN_NS + "lexfile");
    public static final IRI MEMBER_LIST = iri(WN_NS + "memberList");
    public static final IRI DEFINITION_LINK = iri(WN_NS + "definition");
    public static final IRI ILI_DEFINITION_LINK = iri(WN_NS + "iliDefinition");
    public static final IRI EXAMPLE_LINK = iri(WN_NS + "example");
    public static final IRI COUNT_LINK = iri(WN_NS + "count");
    public static final IRI SOURCE_SENSE = iri(WN_NS + "sourceSense");
    public static final IRI PRONUNCIATION_LINK = iri(WN_NS + "pronunciation");
    public static final IRI TAG_LINK = iri(WN_NS + "tag");
    public static final IRI CATEGORY = iri(WN_NS + "category");
    public static final IRI NOTATION = iri(WN_NS + "notation");
    public static final IRI VARIETY = iri(WN_NS + "variety");
    public static final IRI PHONEMIC = iri(WN_NS + "phonemic");
    public static final IRI AUDIO = iri(WN_NS + "audio");
    public static final IRI SENSE_SUBCAT = iri(WN_NS + "senseSubcat");
    public static final IRI SYN_BEHAVIOR = iri(SYNSEM_NS + "synBehavior");
    public static final IRI SYNTACTIC_BEHAVIOUR = iri(SYNSEM_NS + "SyntacticBehaviour");
    public static final IRI DEFINITION = iri(WN_NS + "Definition");
    public static final IRI ILI_DEFINITION = iri(WN_NS + "ILIDefinition");
    public static final IRI EXAMPLE = iri(WN_NS + "Example");
    public static final IRI COUNT = iri(WN_NS + "Count");
    public static final IRI PRONUNCIATION = iri(WN_NS + "Pronunciation");
    public static final IRI TAG = iri(WN_NS + "Tag");
    public static final IRI NOTE = iri(WN_NS + "note");
    public static final IRI WORDNET_STATUS = iri(WN_NS + "status");
    public static final IRI CONFIDENCE_SCORE = iri(WN_NS + "confidenceScore");
    public static final IRI CONCEPTUAL_RELATION = iri(VARTRANS_NS + "ConceptualRelation");
    public static final IRI SENSE_RELATION = iri(VARTRANS_NS + "SenseRelation");
    public static final IRI SOURCE = iri(VARTRANS_NS + "source");
    public static final IRI TARGET = iri(VARTRANS_NS + "target");
    public static final IRI RELATION_CATEGORY = iri(VARTRANS_NS + "category");
    public static final IRI SYNSET_REL_TYPE = iri(WN_NS + "SynsetRelType");
    public static final IRI SENSE_REL_TYPE = iri(WN_NS + "SenseRelType");
    public static final IRI POS_TYPE = iri(WN_NS + "PartOfSpeech");
    public static final IRI WORKFLOW_STATUS = iri(LEXO_NS + "status");
    public static final IRI STATUS_CHANGED_BY = iri(LEXO_NS + "statusChangedBy");

    private WordnetVocabulary() {
    }

    private static IRI iri(String value) {
        return VF.createIRI(value);
    }
}
