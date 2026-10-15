package it.cnr.ilc.lexo.service.data.cpp.output;

@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public class CppRunDetails extends CppRunSummary {
    public String modifiedAt, author, step;
    public it.cnr.ilc.lexo.service.data.text.input.CollocateExtractionRequest search;
    public it.cnr.ilc.lexo.service.data.cpp.input.CppOptionsInput cppOptions;
    public it.cnr.ilc.lexo.service.data.cpp.input.CppCorpusSnapshotInput corpusSnapshot;
    public it.cnr.ilc.lexo.service.data.cpp.input.CppIndexInput index;
    public it.cnr.ilc.lexo.service.data.cpp.input.CppSoftwareInput software;
    public it.cnr.ilc.lexo.service.data.cpp.input.CppExecutionInput execution;
    public java.util.List<String> collocations;
}
