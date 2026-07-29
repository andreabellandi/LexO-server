package it.cnr.ilc.lexo.manager.text;

import java.util.Locale;

/** Admission rules shared by the bulk text endpoint and its tests. */
public final class TextBulkImportValidator {

    private TextBulkImportValidator() {
    }

    public static void requireFileCount(int count, int maximum) {
        if (count <= 0) {
            throw new IllegalArgumentException(
                    "BULK_MISSING_FILES: È richiesto almeno un file TXT o CommonMark");
        }
        if (count > maximum) {
            throw new IllegalArgumentException("BULK_TOO_MANY_FILES: Il bulk contiene "
                    + count + " file; il limite configurato è " + maximum);
        }
    }

    public static void rejectConlluPart(boolean present) {
        if (present) {
            throw conlluNotAllowed();
        }
    }

    public static void requireSupportedFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "BULK_MISSING_FILENAME: Ogni file deve avere un nome originale");
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (TextJobManager.isConlluExtension(lower)) {
            throw conlluNotAllowed();
        }
        if (!TextJobManager.isTextExtension(lower)) {
            throw new IllegalArgumentException(
                    "BULK_UNSUPPORTED_FILE_TYPE: Il bulk accetta soltanto file .txt, .md o .markdown");
        }
    }

    private static IllegalArgumentException conlluNotAllowed() {
        return new IllegalArgumentException(
                "BULK_CONLLU_NOT_ALLOWED: Il bulk non ammette file CoNLL-U");
    }
}
