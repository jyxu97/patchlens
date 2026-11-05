package com.example.importer;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvImporterTest {

    @Test
    void parsesValidRows() throws Exception {
        var in = new ByteArrayInputStream("alice, 10\nbob,20\n".getBytes(StandardCharsets.UTF_8));
        List<CsvImporter.Row> rows = new CsvImporter().importRows(in);
        assertEquals(List.of(new CsvImporter.Row("alice", 10), new CsvImporter.Row("bob", 20)), rows);
    }
}
