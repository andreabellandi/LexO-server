/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package it.cnr.ilc.lexo.service.data.lexicon.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.util.ArrayList;

/**
 *
 * @author andreabellandi
 */
@ApiModel(description = "Input model representing the configuration parameters")
public class Config {

    private ArrayList<_Config> configuration;

    public ArrayList<_Config> getConfiguration() {
        return configuration;
    }

    public void setConfiguration(ArrayList<_Config> configuration) {
        this.configuration = configuration;
    }

    public class _Config {

        private String key;
        private String value;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

    }

//    @ApiModelProperty(value = "Zotero endpoint")
//    private String zoteroEndpoint;
//    @ApiModelProperty(value = "Zotero request prefix")
//    private String zoteroRequestPrefix;
//    @ApiModelProperty(value = "Zotero libray ID")
//    private String zoteroLibrary;
//    @ApiModelProperty(value = "Zotero API version")
//    private int zoteroVersion;
//
//    @ApiModelProperty(value = "base namespace of the lexicon")
//    private String lexiconNamespace;
//    @ApiModelProperty(value = "namespace of lexical concepts")
//    private String lexicalConceptNamespace;
//    @ApiModelProperty(value = "namespace of the bibliography")
//    private String bibliographyNamespace;
//    @ApiModelProperty(value = "namespace of the ontology")
//    private String ontologyNamespace;
//
//    @ApiModelProperty(value = "lexicalization model", allowableValues = "LABEL, SKOS, SKOS_XL")
//    private String skosLexicalizationModel;
//    @ApiModelProperty(value = "default language for SKOS elements visualization")
//    private String skosDefaultLanguageLabel;
//    @ApiModelProperty(value = "allowed languages for SKOS elements")
//    private String skosLanguages;
//
//    public String getZoteroEndpoint() {
//        return zoteroEndpoint;
//    }
//
//    public void setZoteroEndpoint(String zoteroEndpoint) {
//        this.zoteroEndpoint = zoteroEndpoint;
//    }
//
//    public String getZoteroRequestPrefix() {
//        return zoteroRequestPrefix;
//    }
//
//    public void setZoteroRequestPrefix(String zoteroRequestPrefix) {
//        this.zoteroRequestPrefix = zoteroRequestPrefix;
//    }
//
//    public String getZoteroLibrary() {
//        return zoteroLibrary;
//    }
//
//    public void setZoteroLibrary(String zoteroLibrary) {
//        this.zoteroLibrary = zoteroLibrary;
//    }
//
//    public int getZoteroVersion() {
//        return zoteroVersion;
//    }
//
//    public void setZoteroVersion(int zoteroVersion) {
//        this.zoteroVersion = zoteroVersion;
//    }
//
//    public String getLexiconNamespace() {
//        return lexiconNamespace;
//    }
//
//    public void setLexiconNamespace(String lexiconNamespace) {
//        this.lexiconNamespace = lexiconNamespace;
//    }
//
//    public String getLexicalConceptNamespace() {
//        return lexicalConceptNamespace;
//    }
//
//    public void setLexicalConceptNamespace(String lexicalConceptNamespace) {
//        this.lexicalConceptNamespace = lexicalConceptNamespace;
//    }
//
//    public String getBibliographyNamespace() {
//        return bibliographyNamespace;
//    }
//
//    public void setBibliographyNamespace(String bibliographyNamespace) {
//        this.bibliographyNamespace = bibliographyNamespace;
//    }
//
//    public String getOntologyNamespace() {
//        return ontologyNamespace;
//    }
//
//    public void setOntologyNamespace(String ontologyNamespace) {
//        this.ontologyNamespace = ontologyNamespace;
//    }
//
//    public String getSkosLexicalizationModel() {
//        return skosLexicalizationModel;
//    }
//
//    public void setSkosLexicalizationModel(String skosLexicalizationModel) {
//        this.skosLexicalizationModel = skosLexicalizationModel;
//    }
//
//    public String getSkosDefaultLanguageLabel() {
//        return skosDefaultLanguageLabel;
//    }
//
//    public void setSkosDefaultLanguageLabel(String skosDefaultLanguageLabel) {
//        this.skosDefaultLanguageLabel = skosDefaultLanguageLabel;
//    }
//
//    public String getSkosLanguages() {
//        return skosLanguages;
//    }
//
//    public void setSkosLanguages(String skosLanguages) {
//        this.skosLanguages = skosLanguages;
//    }
}
