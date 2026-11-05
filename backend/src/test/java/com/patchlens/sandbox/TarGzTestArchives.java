package com.patchlens.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/** Builds small tar.gz archives (ustar/PAX entries) in memory for extractor tests. */
final class TarGzTestArchives {

    private final ByteArrayOutputStream tar = new ByteArrayOutputStream();

    TarGzTestArchives dir(String name)                { return entry(name, '5', new byte[0], ""); }
    TarGzTestArchives file(String name, String body)  { return entry(name, '0', body.getBytes(StandardCharsets.UTF_8), ""); }
    TarGzTestArchives symlink(String name, String to) { return entry(name, '2', new byte[0], to); }

    /** A file whose real path is carried in a preceding PAX extended header. */
    TarGzTestArchives paxFile(String longName, String body) {
        // PAX record: "<len> path=<value>\n", where <len> counts the whole record including itself
        String record = "path=" + longName + "\n";
        int len = record.length() + 2;
        while ((len + " " + record).length() != len) len = (len + " " + record).length();
        entry("PaxHeader/x", 'x', (len + " " + record).getBytes(StandardCharsets.UTF_8), "");
        return entry("placeholder-name", '0', body.getBytes(StandardCharsets.UTF_8), "");
    }

    byte[] build() throws IOException {
        tar.write(new byte[1024]); // end-of-archive marker
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(tar.toByteArray());
        }
        return gz.toByteArray();
    }

    private TarGzTestArchives entry(String name, char type, byte[] data, String link) {
        byte[] h = new byte[512];
        put(h, 0, 100, name);
        put(h, 100, 8, "0000644");
        put(h, 108, 8, "0000000");
        put(h, 116, 8, "0000000");
        put(h, 124, 12, String.format("%011o", data.length));
        put(h, 136, 12, "00000000000");
        for (int i = 148; i < 156; i++) h[i] = ' ';
        h[156] = (byte) type;
        put(h, 157, 100, link);
        put(h, 257, 6, "ustar");
        put(h, 263, 2, "00");
        long sum = 0;
        for (byte b : h) sum += (b & 0xff);
        put(h, 148, 8, String.format("%06o", sum) + "\0 ");
        tar.writeBytes(h);
        tar.writeBytes(data);
        int pad = (512 - data.length % 512) % 512;
        tar.writeBytes(new byte[pad]);
        return this;
    }

    private static void put(byte[] h, int off, int len, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(b, 0, h, off, Math.min(len, b.length));
    }
}
