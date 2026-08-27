package it.cnr.ilc.lexo.util;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class LogSanitizerTest {

    @Test
    void removesLineBreaksAndControlCharacters() {
        assertThat(LogSanitizer.singleLine("first\r\nsecond\u0000"))
                .isEqualTo("first__second_");
    }

    @Test
    void limitsUntrustedMessages() {
        StringBuilder input = new StringBuilder();
        for (int index = 0; index < 5000; index++) {
            input.append('x');
        }

        assertThat(LogSanitizer.singleLine(input.toString())).hasSize(4096);
    }
}
