package com.example.importer;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvImporterOracleTest {

    static final class TrackingStream extends ByteArrayInputStream {
        boolean closed;
        TrackingStream(String s) { super(s.getBytes(StandardCharsets.UTF_8)); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }

    @Test
    void streamIsClosedWhenARowIsMalformed() {
        TrackingStream in = new TrackingStream("alice,10\nbroken-row\n");
        assertThrows(RuntimeException.class, () -> new CsvImporter().importRows(in));
        assertTrue(in.closed, "input stream must be closed after a parse failure");
    }

    @Test
    void streamIsClosedAfterSuccess() throws Exception {
        TrackingStream in = new TrackingStream("alice,10\n");
        new CsvImporter().importRows(in);
        assertTrue(in.closed);
    }
}
