package it.cnr.ilc.lexo.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class BootstrapResources {

    private BootstrapResources() {
    }

    public static byte[] readBytes(String resource) {
        try (InputStream input = BootstrapResources.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Bootstrap resource not found: " + resource);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read bootstrap resource: " + resource, ex);
        }
    }

    public static String readUtf8(String resource) {
        return new String(readBytes(resource), StandardCharsets.UTF_8);
    }
}
