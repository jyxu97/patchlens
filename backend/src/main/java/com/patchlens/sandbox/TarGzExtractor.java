package com.patchlens.sandbox;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * Minimal, defensive extractor for GitHub repository tarballs ({@code .tar.gz}).
 *
 * <p>Implemented in-process so untrusted archives are never handed to a host {@code tar}:
 * <ul>
 *   <li>The first path component (GitHub's {@code owner-repo-sha/} root) is stripped.</li>
 *   <li>Absolute paths, {@code ..} segments and other unsafe names are rejected.</li>
 *   <li>Symlinks, hardlinks and device entries are skipped — they could point outside the sandbox.</li>
 *   <li>Total extracted bytes and entry counts are capped.</li>
 * </ul>
 * Supports ustar, PAX extended headers ({@code path}) and GNU long names.
 */
public final class TarGzExtractor {

    public record Limits(long maxTotalBytes, int maxEntries) {
        public static Limits defaults() { return new Limits(512L * 1024 * 1024, 100_000); }
    }

    public record Result(int filesWritten, int skippedLinks, long bytesWritten) {}

    private static final int BLOCK = 512;

    private TarGzExtractor() {}

    public static Result extract(InputStream gz, Path dest, Limits limits) throws IOException {
        Files.createDirectories(dest);
        try (InputStream in = new BufferedInputStream(new GZIPInputStream(gz))) {
            byte[] header = new byte[BLOCK];
            String paxPath = null, gnuLongName = null;
            int files = 0, skipped = 0, entries = 0;
            long total = 0;

            while (true) {
                if (!readFully(in, header)) break;
                if (isZeroBlock(header)) break;
                if (++entries > limits.maxEntries()) throw new IOException("Archive exceeds " + limits.maxEntries() + " entries");

                char type = (char) header[156];
                long size = parseOctal(header, 124, 12);
                if (size < 0) throw new IOException("Unsupported tar size encoding");

                if (type == 'x' || type == 'L') {
                    if (size > 1024 * 1024) throw new IOException("Oversized tar extension header");
                    byte[] data = readData(in, size);
                    if (type == 'x') paxPath = parsePaxPath(data);
                    else gnuLongName = cString(data, 0, data.length);
                    continue;
                }
                if (type == 'g') { skipData(in, size); continue; }

                String name = gnuLongName != null ? gnuLongName
                        : paxPath != null ? paxPath
                        : ustarName(header);
                gnuLongName = null;
                paxPath = null;

                String rel = stripFirstComponent(name);
                boolean regular = type == '0' || type == '\0' || type == '7';
                boolean dir = type == '5';

                if (rel.isEmpty()) { skipData(in, size); continue; }
                if (!SafePaths.isSafeRelative(rel)) throw new IOException("Unsafe path in archive: " + name);

                if (dir) {
                    Files.createDirectories(SafePaths.resolveInside(dest, rel));
                    skipData(in, size);
                } else if (regular) {
                    total += size;
                    if (total > limits.maxTotalBytes()) throw new IOException("Archive exceeds " + limits.maxTotalBytes() + " bytes");
                    Path target = SafePaths.resolveInside(dest, rel);
                    Files.createDirectories(target.getParent());
                    try (OutputStream out = Files.newOutputStream(target)) {
                        copyExactly(in, out, size);
                    }
                    skipPadding(in, size);
                    files++;
                } else {
                    // symlink ('2'), hardlink ('1'), char/block devices, fifos — never materialised
                    skipped++;
                    skipData(in, size);
                }
            }
            return new Result(files, skipped, total);
        }
    }

    static String stripFirstComponent(String name) {
        String n = name.startsWith("./") ? name.substring(2) : name;
        int slash = n.indexOf('/');
        if (slash < 0) return "";
        String rest = n.substring(slash + 1);
        return rest.endsWith("/") ? rest.substring(0, rest.length() - 1) : rest;
    }

    private static String ustarName(byte[] h) {
        String name = cString(h, 0, 100);
        String magic = cString(h, 257, 6);
        if (magic.startsWith("ustar")) {
            String prefix = cString(h, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
        }
        return name;
    }

    private static String parsePaxPath(byte[] data) {
        String s = new String(data, StandardCharsets.UTF_8);
        int i = 0;
        String path = null;
        while (i < s.length()) {
            int sp = s.indexOf(' ', i);
            if (sp < 0) break;
            int len;
            try { len = Integer.parseInt(s.substring(i, sp)); } catch (NumberFormatException e) { break; }
            if (len <= 0 || i + len > s.length()) break;
            String record = s.substring(sp + 1, i + len - 1); // drop trailing '\n'
            int eq = record.indexOf('=');
            if (eq > 0 && record.substring(0, eq).equals("path")) path = record.substring(eq + 1);
            i += len;
        }
        return path;
    }

    private static String cString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && end < b.length && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static long parseOctal(byte[] b, int off, int len) {
        if ((b[off] & 0x80) != 0) return -1; // base-256 encoding (files > 8 GiB) — unsupported
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c == 0 || c == ' ') { if (v > 0) break; else continue; }
            if (c < '0' || c > '7') return -1;
            v = v * 8 + (c - '0');
        }
        return v;
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int n = 0;
        while (n < buf.length) {
            int r = in.read(buf, n, buf.length - n);
            if (r < 0) { if (n == 0) return false; throw new EOFException("Truncated tar header"); }
            n += r;
        }
        return true;
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        byte[] data = new byte[(int) size];
        if (size > 0 && !readFully(in, data)) throw new EOFException("Truncated tar data");
        skipPadding(in, size);
        return data;
    }

    private static void copyExactly(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buf = new byte[8192];
        long remaining = size;
        while (remaining > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (r < 0) throw new EOFException("Truncated tar entry");
            out.write(buf, 0, r);
            remaining -= r;
        }
    }

    private static void skipData(InputStream in, long size) throws IOException {
        skipExactly(in, size);
        skipPadding(in, size);
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long pad = (BLOCK - (size % BLOCK)) % BLOCK;
        skipExactly(in, pad);
    }

    private static void skipExactly(InputStream in, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long s = in.skip(remaining);
            if (s <= 0) {
                if (in.read() < 0) throw new EOFException("Truncated tar stream");
                s = 1;
            }
            remaining -= s;
        }
    }
}
