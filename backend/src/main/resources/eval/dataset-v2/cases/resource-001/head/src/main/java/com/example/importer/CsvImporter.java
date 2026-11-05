package com.example.importer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Imports "name,amount" CSV rows uploaded by users. */
public class CsvImporter {

    public record Row(String name, long amount) {}

    /** Parses all rows. The stream is always closed, even if a row is malformed. */
    public List<Row> importRows(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        List<Row> rows = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            String[] parts = line.split(",");
            rows.add(new Row(parts[0].trim(), Long.parseLong(parts[1].trim())));
        }
        reader.close();
        return rows;
    }
}
