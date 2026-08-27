package it.cnr.ilc.lexo.util;

/** Prevents user-controlled values from forging or expanding log events. */
public final class LogSanitizer {

    private static final int MAX_MESSAGE_LENGTH = 4096;

    private LogSanitizer() {
    }

    public static String singleLine(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sanitized = new StringBuilder(
                Math.min(value.length(), MAX_MESSAGE_LENGTH));
        for (int index = 0;
             index < value.length() && sanitized.length() < MAX_MESSAGE_LENGTH;
             index++) {
            char character = value.charAt(index);
            if (character == '\r' || character == '\n'
                    || Character.isISOControl(character)) {
                sanitized.append('_');
            } else {
                sanitized.append(character);
            }
        }
        return sanitized.toString();
    }
}
