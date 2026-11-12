package it.cnr.ilc.lexo.service.data.cpp.input;

public class CppRunCreateRequest {
    public it.cnr.ilc.lexo.service.data.text.input.CollocateExtractionRequest search;
    public CppOptionsInput cppOptions;
    public CppCorpusSnapshotInput corpusSnapshot;
    public CppIndexInput index;
    public CppSoftwareInput software;
    public CppExecutionInput execution;
    public String author;
    public java.util.List<String> collocations;
}
