package it.cnr.ilc.lexo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import it.cnr.ilc.lexo.manager.text.CorpusManager;
import it.cnr.ilc.lexo.manager.text.Iso639LanguageValidator;
import it.cnr.ilc.lexo.manager.text.TextBulkImportValidator;
import it.cnr.ilc.lexo.manager.text.TextBulkJobManager;
import it.cnr.ilc.lexo.manager.text.TextBulkJobManager.BulkUpload;
import it.cnr.ilc.lexo.manager.text.TextCatalogManager;
import it.cnr.ilc.lexo.manager.text.TextJobManager;
import it.cnr.ilc.lexo.manager.text.TextValidationException;
import it.cnr.ilc.lexo.manager.text.TextJobManager.TextJobInfo;
import it.cnr.ilc.lexo.manager.text.TextJobManager.UploadKind;
import it.cnr.ilc.lexo.service.data.lexicon.input.converter.CancelRequest;
import it.cnr.ilc.lexo.service.data.text.output.CorpusRecord;
import it.cnr.ilc.lexo.service.data.text.output.BulkTextJob;
import it.cnr.ilc.lexo.service.data.text.output.TextRecord;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import org.apache.log4j.Level;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;

/** REST endpoints for persistent text corpora represented in RDF/NIF. */
@javax.ws.rs.Path("texts")
@Api("Text Corpus NIF")
public class Texts extends Service {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MAX_TEXT_BYTES = longProperty(
            "lexo.text.maxTextBytes", TextJobManager.DEFAULT_MAX_TEXT_BYTES);
    private static final long MAX_CONLLU_BYTES = longProperty(
            "lexo.text.maxConlluBytes", TextJobManager.DEFAULT_MAX_CONLLU_BYTES);
    private static final int MAX_BULK_FILES = intProperty(
            "lexo.text.maxBulkFiles", 100);
    private static final long MAX_BULK_BYTES = longProperty(
            "lexo.text.maxBulkBytes", 200L * 1024L * 1024L);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Text catalog",
            notes = "This method returns all texts stored in LexOTexts, optionally filtered by corpus, with their size, linguistic counts, attestation and annotation counts, and available metadata")
    public Response catalog(
            @HeaderParam("Authorization") String key,
            @ApiParam(
                    name = "corpusId",
                    value = "optional corpus id used to restrict the returned texts",
                    example = "7d444840-9dc0-11d1-b245-5ffdce74fad2",
                    required = false)
            @QueryParam("corpusId") String corpusId) {
        try {
            checkKey(key);
            log(Level.INFO, "/texts: catalog requested"
                    + (corpusId == null || corpusId.trim().isEmpty()
                            ? "" : " for corpusId=" + corpusId.trim()));
            return json(TextCatalogManager.get().list(corpusId));
        } catch (IllegalArgumentException e) {
            return plain(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (RuntimeException e) {
            log(Level.ERROR, "/texts: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            return plain(Response.Status.INTERNAL_SERVER_ERROR, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            return unauthorized("/texts");
        }
    }

    @POST
    @javax.ws.rs.Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Text upload",
            notes = "This method uploads one TXT or CommonMark file, its required ISO 639 language code and an optional CoNLL-U file, and returns the generated file id")
    @ApiImplicitParam(
            name = "language",
            value = "required text language code present in ISO 639-1, ISO 639-2 or ISO 639-3",
            example = "it",
            required = true,
            dataType = "string",
            paramType = "form")
    public Response upload(
            @HeaderParam("Authorization") String key,
            @ApiParam(
                    name = "file",
                    value = "multipart request containing one TXT/CommonMark file in the file field and an optional CoNLL-U file in the conllu field",
                    required = true)
            FormDataMultiPart multiPart) {
        String fileId = UUID.randomUUID().toString();
        try {
            checkKey(key);
            if (multiPart == null) {
                return plain(Response.Status.BAD_REQUEST, "Missing multipart request");
            }

            List<FormDataBodyPart> parts = new ArrayList<FormDataBodyPart>();
            addAll(parts, multiPart.getFields("file"));
            addAll(parts, multiPart.getFields("conllu"));
            if (parts.isEmpty()) {
                return plain(Response.Status.BAD_REQUEST, "Missing file");
            }

            List<FormDataBodyPart> languageParts = multiPart.getFields("language");
            if (languageParts != null && languageParts.size() > 1) {
                return plain(Response.Status.BAD_REQUEST,
                        "INVALID_LANGUAGE: È ammesso un solo campo language");
            }
            FormDataBodyPart languagePart = languageParts == null || languageParts.isEmpty()
                    ? null : languageParts.get(0);
            String language = TextJobManager.get().saveUploadLanguage(fileId,
                    languagePart == null ? null : languagePart.getValue());

            String textFileName = null;
            String conlluFileName = null;
            for (FormDataBodyPart part : parts) {
                if (part == null) {
                    continue;
                }
                FormDataContentDisposition metadata = part.getFormDataContentDisposition();
                String name = metadata == null ? null : metadata.getFileName();
                if (name == null || name.trim().isEmpty()) {
                    TextJobManager.get().cleanupUpload(fileId);
                    return plain(Response.Status.BAD_REQUEST, "Missing original filename");
                }
                String lower = name.toLowerCase(Locale.ROOT);
                UploadKind kind;
                long maxBytes;
                if (TextJobManager.isTextExtension(lower)) {
                    if (textFileName != null) {
                        TextJobManager.get().cleanupUpload(fileId);
                        return plain(Response.Status.BAD_REQUEST, "Only one TXT/Markdown file is allowed");
                    }
                    kind = UploadKind.TEXT;
                    maxBytes = MAX_TEXT_BYTES;
                    textFileName = name;
                } else if (TextJobManager.isConlluExtension(lower)) {
                    if (conlluFileName != null) {
                        TextJobManager.get().cleanupUpload(fileId);
                        return plain(Response.Status.BAD_REQUEST, "Only one CoNLL-U file is allowed");
                    }
                    kind = UploadKind.CONLLU;
                    maxBytes = MAX_CONLLU_BYTES;
                    conlluFileName = name;
                } else {
                    TextJobManager.get().cleanupUpload(fileId);
                    return plain(Response.Status.UNSUPPORTED_MEDIA_TYPE,
                            "Allowed extensions: .txt, .md, .markdown, .conllu, .conll-u, .conll");
                }

                try (InputStream input = part.getEntityAs(InputStream.class)) {
                    TextJobManager.get().saveUpload(fileId, input, name, kind, maxBytes);
                }
            }

            if (!TextJobManager.get().hasTextUpload(fileId)) {
                TextJobManager.get().cleanupUpload(fileId);
                return plain(Response.Status.BAD_REQUEST,
                        "A TXT/Markdown file is required; CoNLL-U alone is not sufficient");
            }

            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("fileId", fileId);
            response.put("originalFileName", textFileName);
            response.put("language", language);
            if (conlluFileName != null) {
                response.put("conlluFileName", conlluFileName);
            }
            log(Level.INFO, "/texts/upload: uploaded fileId=" + fileId
                    + " text=" + textFileName + " conllu=" + conlluFileName);
            return json(response);
        } catch (IOException e) {
            TextJobManager.get().cleanupUpload(fileId);
            log(Level.ERROR, "/texts/upload: " + e.getMessage());
            Response.Status status = e.getMessage() != null && e.getMessage().contains("exceeds")
                    ? Response.Status.REQUEST_ENTITY_TOO_LARGE : Response.Status.BAD_REQUEST;
            return plain(status, e.getMessage());
        } catch (IllegalArgumentException e) {
            TextJobManager.get().cleanupUpload(fileId);
            log(Level.ERROR, "/texts/upload: " + e.getMessage());
            return plain(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            TextJobManager.get().cleanupUpload(fileId);
            return unauthorized("/texts/upload");
        } catch (Throwable e) {
            TextJobManager.get().cleanupUpload(fileId);
            log(Level.ERROR, "/texts/upload: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            return plain(Response.Status.INTERNAL_SERVER_ERROR,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            if (multiPart != null) {
                try {
                    multiPart.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    @POST
    @javax.ws.rs.Path("/bulk")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Bulk text upload and NIF conversion",
            notes = "This method validates and uploads multiple TXT/CommonMark files with one shared ISO 639 language, then starts an independent asynchronous NIF conversion for each document. CoNLL-U is not allowed in bulk requests")
    @ApiImplicitParam(
            name = "language",
            value = "single required ISO 639 language code applied to every text in the bulk",
            example = "it",
            required = true,
            dataType = "string",
            paramType = "form")
    public Response uploadBulk(
            @HeaderParam("Authorization") String key,
            @ApiParam(
                    name = "file",
                    value = "multipart request containing one or more .txt, .md or .markdown file fields",
                    required = true)
            FormDataMultiPart multiPart,
            @ApiParam(
                    name = "corpusId",
                    value = "optional id of the corpus to which every successfully converted text is added",
                    example = "7d444840-9dc0-11d1-b245-5ffdce74fad2",
                    required = false)
            @QueryParam("corpusId") String corpusId) {
        List<String> stagedFileIds = new ArrayList<String>();
        try {
            checkKey(key);
            if (multiPart == null) {
                return bulkError(Response.Status.BAD_REQUEST, "BULK_MISSING_REQUEST",
                        "Missing multipart request");
            }

            List<FormDataBodyPart> conlluParts = multiPart.getFields("conllu");
            TextBulkImportValidator.rejectConlluPart(
                    conlluParts != null && !conlluParts.isEmpty());

            List<FormDataBodyPart> fileParts = multiPart.getFields("file");
            int fileCount = fileParts == null ? 0 : fileParts.size();
            TextBulkImportValidator.requireFileCount(fileCount, MAX_BULK_FILES);

            for (FormDataBodyPart part : fileParts) {
                FormDataContentDisposition metadata = part == null
                        ? null : part.getFormDataContentDisposition();
                TextBulkImportValidator.requireSupportedFileName(
                        metadata == null ? null : metadata.getFileName());
            }

            List<FormDataBodyPart> languageParts = multiPart.getFields("language");
            if (languageParts != null && languageParts.size() > 1) {
                return bulkError(Response.Status.BAD_REQUEST, "INVALID_LANGUAGE",
                        "È ammesso un solo campo language per l'intero bulk");
            }
            FormDataBodyPart languagePart = languageParts == null || languageParts.isEmpty()
                    ? null : languageParts.get(0);
            String language = Iso639LanguageValidator.get().requireValid(
                    languagePart == null ? null : languagePart.getValue());

            String selectedCorpusId = corpusId == null || corpusId.trim().isEmpty()
                    ? null : corpusId.trim();
            if (selectedCorpusId != null) {
                try {
                    CorpusManager.get().requireCorpusUri(selectedCorpusId);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "INVALID_CORPUS: " + e.getMessage(), e);
                }
            }

            long totalBytes = 0L;
            List<BulkUpload> uploads = new ArrayList<BulkUpload>(fileCount);
            for (FormDataBodyPart part : fileParts) {
                String fileId = UUID.randomUUID().toString();
                stagedFileIds.add(fileId);
                String originalName = part.getFormDataContentDisposition().getFileName();
                TextJobManager.get().saveUploadLanguage(fileId, language);
                long remaining = MAX_BULK_BYTES - totalBytes;
                if (remaining <= 0L) {
                    throw new BulkSizeException("BULK_SIZE_LIMIT_EXCEEDED",
                            "Il bulk supera il limite configurato di " + MAX_BULK_BYTES + " byte");
                }
                long itemLimit = Math.min(MAX_TEXT_BYTES, remaining);
                Path stored;
                try (InputStream input = part.getEntityAs(InputStream.class)) {
                    stored = TextJobManager.get().saveUpload(fileId, input, originalName,
                            UploadKind.TEXT, itemLimit);
                } catch (IOException e) {
                    if (e.getMessage() == null
                            || !e.getMessage().contains("exceeds configured limit")) {
                        throw e;
                    }
                    String code = itemLimit < MAX_TEXT_BYTES
                            ? "BULK_SIZE_LIMIT_EXCEEDED" : "BULK_FILE_TOO_LARGE";
                    throw new BulkSizeException(code, e.getMessage(), e);
                }
                totalBytes += Files.size(stored);
                uploads.add(new BulkUpload(fileId, stored.getFileName().toString()));
            }

            String bulkId = UUID.randomUUID().toString();
            BulkTextJob bulk = TextBulkJobManager.get().start(
                    bulkId, language, selectedCorpusId, uploads);
            stagedFileIds.clear();
            log(Level.INFO, "/texts/bulk: accepted bulkId=" + bulkId
                    + " files=" + fileCount + " language=" + language);
            return json(Response.Status.ACCEPTED, bulk);
        } catch (BulkSizeException e) {
            cleanupBulkUploads(stagedFileIds);
            log(Level.ERROR, "/texts/bulk: " + e.getMessage());
            return bulkError(Response.Status.REQUEST_ENTITY_TOO_LARGE,
                    e.code, e.getMessage());
        } catch (IllegalArgumentException e) {
            cleanupBulkUploads(stagedFileIds);
            log(Level.ERROR, "/texts/bulk: " + e.getMessage());
            return bulkError(Response.Status.BAD_REQUEST,
                    errorCode(e.getMessage(), "INVALID_BULK_REQUEST"), e.getMessage());
        } catch (IOException e) {
            cleanupBulkUploads(stagedFileIds);
            log(Level.ERROR, "/texts/bulk: " + e.getMessage());
            return bulkError(Response.Status.BAD_REQUEST, "BULK_UPLOAD_FAILED", e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            cleanupBulkUploads(stagedFileIds);
            return unauthorized("/texts/bulk");
        } catch (Throwable e) {
            cleanupBulkUploads(stagedFileIds);
            log(Level.ERROR, "/texts/bulk: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            return bulkError(Response.Status.INTERNAL_SERVER_ERROR, "BULK_INTERNAL_ERROR",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            if (multiPart != null) {
                try {
                    multiPart.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    @GET
    @javax.ws.rs.Path("/bulk/{bulkId}/status")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Bulk text conversion status",
            notes = "This method returns aggregate and per-document states for an accepted bulk text conversion")
    public Response bulkStatus(
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "bulkId", value = "id returned by the bulk text upload service",
                    required = true)
            @PathParam("bulkId") String bulkId) {
        try {
            checkKey(key);
            BulkTextJob bulk = TextBulkJobManager.get().get(bulkId);
            if (bulk == null) {
                return bulkError(Response.Status.NOT_FOUND, "BULK_NOT_FOUND",
                        "Bulk text job not found: " + bulkId);
            }
            return json(bulk);
        } catch (AuthorizationException | ServiceException e) {
            return unauthorized("/texts/bulk/{bulkId}/status");
        }
    }

    @POST
    @javax.ws.rs.Path("/{fileId}/convert")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Text NIF conversion",
            notes = "This method starts the asynchronous conversion of an uploaded text to NIF and optionally adds it to a corpus")
    public Response convert(
            @HeaderParam("Authorization") String key,
            @ApiParam(
                    name = "fileId",
                    value = "id returned by the text upload service",
                    example = "550e8400-e29b-41d4-a716-446655440000",
                    required = true)
            @PathParam("fileId") String fileId,
            @ApiParam(
                    name = "corpusId",
                    value = "optional id of the corpus to which the converted text must be added",
                    example = "7d444840-9dc0-11d1-b245-5ffdce74fad2",
                    required = false)
            @QueryParam("corpusId") String corpusId) {
        try {
            checkKey(key);
            log(Level.INFO, "/texts/{fileId}/convert: required for id " + fileId);
            return json(TextJobManager.get().startConversion(fileId, corpusId));
        } catch (IllegalStateException e) {
            return plain(Response.Status.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            return plain(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            return unauthorized("/texts/{fileId}/convert");
        }
    }

    @POST
    @javax.ws.rs.Path("/corpora")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Corpus creation",
            notes = "This method creates an empty NIF corpus from a TXT file containing only the supported metadata header")
    public Response createCorpus(
            @HeaderParam("Authorization") String key,
            @ApiParam(
                    name = "file",
                    value = "multipart request containing exactly one TXT corpus metadata file in the file field",
                    required = true)
            FormDataMultiPart multiPart) {
        String corpusId = UUID.randomUUID().toString();
        try {
            checkKey(key);
            if (multiPart == null) {
                return plain(Response.Status.BAD_REQUEST, "Missing multipart request");
            }
            List<FormDataBodyPart> parts = multiPart.getFields("file");
            if (parts == null || parts.size() != 1 || parts.get(0) == null) {
                return plain(Response.Status.BAD_REQUEST,
                        "Exactly one .txt corpus metadata file is required");
            }
            FormDataBodyPart part = parts.get(0);
            FormDataContentDisposition disposition = part.getFormDataContentDisposition();
            String name = disposition == null ? null : disposition.getFileName();
            if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".txt")) {
                return plain(Response.Status.UNSUPPORTED_MEDIA_TYPE,
                        "The corpus descriptor must be a .txt file");
            }
            try (InputStream input = part.getEntityAs(InputStream.class)) {
                CorpusRecord record = CorpusManager.get().create(
                        corpusId, input, name, MAX_TEXT_BYTES);
                log(Level.INFO, "/texts/corpora: created corpusId=" + corpusId);
                return json(record);
            }
        } catch (TextValidationException | IllegalArgumentException e) {
            return plain(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            return plain(Response.Status.CONFLICT, e.getMessage());
        } catch (IOException e) {
            Response.Status status = e.getMessage() != null && e.getMessage().contains("exceeds")
                    ? Response.Status.REQUEST_ENTITY_TOO_LARGE
                    : Response.Status.INTERNAL_SERVER_ERROR;
            return plain(status, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            return unauthorized("/texts/corpora");
        } finally {
            if (multiPart != null) {
                try {
                    multiPart.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    @GET
    @javax.ws.rs.Path("/corpora/{corpusId}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Corpus metadata retrieval",
            notes = "This method returns the corpus record, its metadata and the texts currently assigned to it")
    public Response corpusRecord(
            @HeaderParam("Authorization") String key,
            @ApiParam(
                    name = "corpusId",
                    value = "corpus id returned by the corpus creation service",
                    example = "7d444840-9dc0-11d1-b245-5ffdce74fad2",
                    required = true)
            @PathParam("corpusId") String corpusId) {
        try {
            checkKey(key);
            CorpusRecord record = CorpusManager.get().getRecord(corpusId);
            return record == null
                    ? plain(Response.Status.NOT_FOUND, "Corpus not found") : json(record);
        } catch (IllegalArgumentException e) {
            return plain(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            return plain(Response.Status.INTERNAL_SERVER_ERROR, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            return unauthorized("/texts/corpora/{corpusId}");
        }
    }

    @GET
    @javax.ws.rs.Path("/corpora/{corpusId}/nif")
    @Produces("text/turtle")
    @ApiOperation(value = "Corpus NIF download",
            notes = "This method downloads the corpus NIF graph serialized as Turtle")
    public Response corpusNif(
            @HeaderParam("Authorization") String key,
            @ApiParam(
                    name = "corpusId",
                    value = "id of the corpus whose NIF graph must be downloaded",
                    example = "7d444840-9dc0-11d1-b245-5ffdce74fad2",
                    required = true)
            @PathParam("corpusId") String corpusId) {
        try {
            checkKey(key);
            CorpusManager manager = CorpusManager.get();
            return !manager.hasNif(corpusId)
                    ? plain(Response.Status.NOT_FOUND, "Corpus NIF not found")
                    : streamNif(output -> manager.writeNif(corpusId, output),
                            corpusId + ".ttl");
        } catch (IllegalArgumentException e) {
            return plain(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            return unauthorized("/texts/corpora/{corpusId}/nif");
        }
    }

    @GET
    @javax.ws.rs.Path("/corpora/{corpusId}/original")
    @ApiOperation(value = "Corpus descriptor download",
            notes = "This method downloads the original TXT metadata descriptor used to create the corpus")
    public Response corpusOriginal(
            @HeaderParam("Authorization") String key,
            @ApiParam(
                    name = "corpusId",
                    value = "id of the corpus whose original descriptor must be downloaded",
                    example = "7d444840-9dc0-11d1-b245-5ffdce74fad2",
                    required = true)
            @PathParam("corpusId") String corpusId) {
        try {
            checkKey(key);
            CorpusRecord record = CorpusManager.get().getRecord(corpusId);
            Path path = record == null ? null : CorpusManager.get().getOriginal(corpusId);
            return path == null || !Files.exists(path)
                    ? plain(Response.Status.NOT_FOUND, "Corpus descriptor not found")
                    : stream(path, "text/plain; charset=UTF-8", record.originalFileName);
        } catch (IllegalArgumentException e) {
            return plain(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            return unauthorized("/texts/corpora/{corpusId}/original");
        }
    }

    @DELETE
    @javax.ws.rs.Path("/corpora/{corpusId}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Corpus deletion",
            notes = "This method deletes a corpus NIF graph and its persisted descriptor without deleting the member text graphs")
    public Response deleteCorpus(
            @HeaderParam("Authorization") String key,
            @ApiParam(
                    name = "corpusId",
                    value = "id of the corpus to delete",
                    example = "7d444840-9dc0-11d1-b245-5ffdce74fad2",
                    required = true)
            @PathParam("corpusId") String corpusId) {
        try {
            checkKey(key);
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("deleted", Boolean.valueOf(CorpusManager.get().delete(corpusId)));
            return json(response);
        } catch (IOException e) {
            return plain(Response.Status.INTERNAL_SERVER_ERROR, e.getMessage());
        } catch (IllegalArgumentException e) {
            return plain(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            return plain(Response.Status.CONFLICT, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            return unauthorized("DELETE /texts/corpora/{corpusId}");
        }
    }

    @GET
    @javax.ws.rs.Path("/{fileId}/status")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Text conversion status",
            notes = "This method returns the current asynchronous conversion status for an uploaded text")
    public Response status(
            @HeaderParam("Authorization") String key,
            @ApiParam(
                    name = "fileId",
                    value = "id of the uploaded text whose conversion status must be returned",
                    example = "550e8400-e29b-41d4-a716-446655440000",
                    required = true)
            @PathParam("fileId") String fileId) {
        try {
            checkKey(key);
            Collection<TextJobInfo> jobs = TextJobManager.get().getAllJobsFor(fileId);
            return json(jobs);
        } catch (AuthorizationException | ServiceException e) {
            return unauthorized("/texts/{fileId}/status");
        }
    }

    @POST
    @javax.ws.rs.Path("/{fileId}/cancel")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Text conversion cancellation",
            notes = "This method requests cancellation of the asynchronous NIF conversion for an uploaded text")
    public Response cancel(
            @HeaderParam("Authorization") String key,
            @ApiParam(
                    name = "fileId",
                    value = "id of the uploaded text whose conversion must be cancelled",
                    example = "550e8400-e29b-41d4-a716-446655440000",
                    required = true)
            @PathParam("fileId") String fileId,
            @ApiParam(
                    name = "request",
                    value = "optional cancellation request; when supplied, type must be CONVERT",
                    required = false)
            CancelRequest request) {
        try {
            checkKey(key);
            if (request != null && request.type != null
                    && !"CONVERT".equalsIgnoreCase(request.type.trim())) {
                return plain(Response.Status.BAD_REQUEST,
                        "Unknown type: " + request.type + ". The text service supports CONVERT");
            }
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("cancelled", Boolean.valueOf(TextJobManager.get().cancel(fileId)));
            return json(response);
        } catch (AuthorizationException | ServiceException e) {
            return unauthorized("/texts/{fileId}/cancel");
        }
    }

    @GET
    @javax.ws.rs.Path("/{fileId}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Text metadata retrieval",
            notes = "This method returns the persisted record and metadata for a converted text")
    public Response record(
            @HeaderParam("Authorization") String key,
            @ApiParam(
                    name = "fileId",
                    value = "id of the text whose record must be returned",
                    example = "550e8400-e29b-41d4-a716-446655440000",
                    required = true)
            @PathParam("fileId") String fileId) {
        try {
            checkKey(key);
            TextRecord record = TextJobManager.get().getRecord(fileId);
            return record == null ? plain(Response.Status.NOT_FOUND, "Text record not found") : json(record);
        } catch (IllegalStateException e) {
            return plain(Response.Status.INTERNAL_SERVER_ERROR, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            return unauthorized("/texts/{fileId}");
        }
    }

    @GET
    @javax.ws.rs.Path("/{fileId}/nif")
    @Produces("text/turtle")
    @ApiOperation(value = "Text NIF download",
            notes = "This method downloads the converted text NIF graph serialized as Turtle")
    public Response nif(
            @HeaderParam("Authorization") String key,
            @ApiParam(
                    name = "fileId",
                    value = "id of the text whose NIF graph must be downloaded",
                    example = "550e8400-e29b-41d4-a716-446655440000",
                    required = true)
            @PathParam("fileId") String fileId) {
        return artifact(key, fileId, Artifact.NIF);
    }

    @GET
    @javax.ws.rs.Path("/{fileId}/original")
    @ApiOperation(value = "Original text download",
            notes = "This method downloads the original TXT or CommonMark file supplied during upload")
    public Response original(
            @HeaderParam("Authorization") String key,
            @ApiParam(
                    name = "fileId",
                    value = "id of the text whose original file must be downloaded",
                    example = "550e8400-e29b-41d4-a716-446655440000",
                    required = true)
            @PathParam("fileId") String fileId) {
        return artifact(key, fileId, Artifact.ORIGINAL);
    }

    @GET
    @javax.ws.rs.Path("/{fileId}/canonical")
    @Produces(MediaType.TEXT_PLAIN)
    @ApiOperation(value = "Canonical text download",
            notes = "This method downloads the normalized plain text used to generate NIF character offsets")
    public Response canonical(
            @HeaderParam("Authorization") String key,
            @ApiParam(
                    name = "fileId",
                    value = "id of the text whose canonical representation must be downloaded",
                    example = "550e8400-e29b-41d4-a716-446655440000",
                    required = true)
            @PathParam("fileId") String fileId) {
        return artifact(key, fileId, Artifact.CANONICAL);
    }

    @GET
    @javax.ws.rs.Path("/{fileId}/conllu")
    @Produces("text/x-conllu")
    @ApiOperation(value = "CoNLL-U download",
            notes = "This method downloads the optional CoNLL-U file associated with the converted text")
    public Response conllu(
            @HeaderParam("Authorization") String key,
            @ApiParam(
                    name = "fileId",
                    value = "id of the text whose CoNLL-U file must be downloaded",
                    example = "550e8400-e29b-41d4-a716-446655440000",
                    required = true)
            @PathParam("fileId") String fileId) {
        return artifact(key, fileId, Artifact.CONLLU);
    }

    @DELETE
    @javax.ws.rs.Path("/{fileId}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Text deletion",
            notes = "This method deletes the text NIF graph, detaches it from its corpus, clears its attestation and annotation graphs in LexOLexica, and removes all persisted files")
    public Response delete(
            @HeaderParam("Authorization") String key,
            @ApiParam(
                    name = "fileId",
                    value = "id of the text to delete",
                    example = "550e8400-e29b-41d4-a716-446655440000",
                    required = true)
            @PathParam("fileId") String fileId) {
        try {
            checkKey(key);
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("deleted", Boolean.valueOf(TextJobManager.get().delete(fileId)));
            return json(response);
        } catch (IOException e) {
            return plain(Response.Status.INTERNAL_SERVER_ERROR, e.getMessage());
        } catch (IllegalArgumentException e) {
            return plain(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            return unauthorized("DELETE /texts/{fileId}");
        }
    }

    private Response artifact(String key, String fileId, Artifact artifact) {
        try {
            checkKey(key);
            TextJobManager manager = TextJobManager.get();
            TextRecord record = manager.getRecord(fileId);
            if (record == null) {
                return plain(Response.Status.NOT_FOUND, "Text record not found");
            }
            Path path;
            String mediaType;
            String downloadName;
            switch (artifact) {
                case NIF:
                    return !manager.hasNif(fileId)
                            ? plain(Response.Status.NOT_FOUND, "Text NIF not found")
                            : streamNif(output -> manager.writeNif(fileId, output),
                                    fileId + ".ttl");
                case CANONICAL:
                    String canonical = manager.getCanonical(fileId);
                    return canonical == null
                            ? plain(Response.Status.NOT_FOUND, "Canonical text not found")
                            : Response.ok(canonical)
                                    .type("text/plain; charset=UTF-8")
                                    .header("Content-Disposition", "attachment; filename=\""
                                            + safeHeaderFileName(fileId + "-canonical.txt") + "\"")
                                    .build();
                case ORIGINAL:
                    path = manager.getOriginal(fileId);
                    mediaType = originalMediaType(record.originalFileName);
                    downloadName = record.originalFileName;
                    break;
                case CONLLU:
                    path = manager.getConllu(fileId);
                    mediaType = "text/x-conllu; charset=UTF-8";
                    downloadName = record.conlluFileName;
                    break;
                default:
                    throw new IllegalStateException("Unsupported artifact");
            }
            if (path == null || !Files.exists(path)) {
                return plain(Response.Status.NOT_FOUND,
                        artifact == Artifact.CONLLU
                                ? "No CoNLL-U file for this text" : "Artifact not found");
            }
            return stream(path, mediaType, downloadName);
        } catch (IllegalArgumentException e) {
            return plain(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            return plain(Response.Status.INTERNAL_SERVER_ERROR, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            return unauthorized("/texts/{fileId}/" + artifact.name().toLowerCase(Locale.ROOT));
        }
    }

    private static Response streamNif(StreamingOutput output, String downloadName) {
        return Response.ok(output)
                .type("text/turtle; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\""
                        + safeHeaderFileName(downloadName) + "\"")
                .build();
    }

    private static Response stream(Path path, String mediaType, String downloadName) {
        final Path served = path;
        StreamingOutput output = stream -> {
            try (InputStream input = Files.newInputStream(served)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    stream.write(buffer, 0, read);
                }
                stream.flush();
            }
        };
        return Response.ok(output)
                .type(mediaType)
                .header("Content-Disposition", "attachment; filename=\""
                        + safeHeaderFileName(downloadName) + "\"")
                .build();
    }

    private Response unauthorized(String endpoint) {
        String username = authenticationData.getUsername() == null ? "" : authenticationData.getUsername();
        log(Level.ERROR, endpoint + ": " + username + " not authorized");
        return plain(Response.Status.BAD_REQUEST, username + " not authorized");
    }

    private static Response json(Object body) {
        return json(Response.Status.OK, body);
    }

    private static Response json(Response.Status status, Object body) {
        try {
            return Response.status(status).type(MediaType.APPLICATION_JSON)
                    .entity(MAPPER.writeValueAsString(body)).build();
        } catch (JsonProcessingException e) {
            return plain(Response.Status.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private static Response bulkError(Response.Status status, String code, String message) {
        Map<String, String> error = new LinkedHashMap<String, String>();
        error.put("code", code);
        error.put("message", message == null ? "" : message);
        return json(status, error);
    }

    private static String errorCode(String message, String fallback) {
        if (message == null) {
            return fallback;
        }
        int separator = message.indexOf(':');
        if (separator <= 0) {
            return fallback;
        }
        String candidate = message.substring(0, separator);
        return candidate.matches("[A-Z][A-Z0-9_]*") ? candidate : fallback;
    }

    private static void cleanupBulkUploads(List<String> fileIds) {
        for (String fileId : fileIds) {
            TextJobManager.get().cleanupUpload(fileId);
        }
    }

    private static Response plain(Response.Status status, String message) {
        return Response.status(status).type(MediaType.TEXT_PLAIN)
                .entity(message == null ? "" : message).build();
    }

    private static void addAll(List<FormDataBodyPart> target, List<FormDataBodyPart> source) {
        if (source != null) {
            target.addAll(source);
        }
    }

    private static String originalMediaType(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown")
                ? "text/markdown; charset=UTF-8" : "text/plain; charset=UTF-8";
    }

    private static String safeHeaderFileName(String fileName) {
        if (fileName == null) {
            return "download";
        }
        return fileName.replace("\\", "_").replace("\"", "_")
                .replace("\r", "_").replace("\n", "_");
    }

    private static long longProperty(String name, long fallback) {
        String value = System.getProperty(name);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int intProperty(String name, int fallback) {
        String value = System.getProperty(name);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final class BulkSizeException extends IOException {
        final String code;

        BulkSizeException(String code, String message) {
            super(message);
            this.code = code;
        }

        BulkSizeException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }
    }

    private enum Artifact {
        NIF, ORIGINAL, CANONICAL, CONLLU
    }
}
