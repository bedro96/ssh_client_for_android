package com.bedro96.sshclient;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

final class Utf8ChunkReader {
    private final Reader reader;
    private final char[] buf;

    Utf8ChunkReader(InputStream in) {
        this(in, 4096);
    }

    Utf8ChunkReader(InputStream in, int bufferSize) {
        this.reader = new InputStreamReader(in, StandardCharsets.UTF_8);
        this.buf = new char[bufferSize];
    }

    String readChunk() throws IOException {
        int n = reader.read(buf);
        if (n < 0) { return null; }
        if (n == 0) { return ""; }
        return new String(buf, 0, n);
    }
}
