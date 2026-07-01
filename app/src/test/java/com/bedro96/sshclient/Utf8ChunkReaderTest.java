package com.bedro96.sshclient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class Utf8ChunkReaderTest {

    private Utf8ChunkReaderTest() { }

    public static void main(String[] args) throws Exception {
        testSplitKoreanAndEmojiAcrossChunks();
        System.out.println("UTF8 CHUNK TESTS PASSED");
    }

    private static void testSplitKoreanAndEmojiAcrossChunks() throws Exception {
        String expected = "prefix 한글🙂 suffix";
        byte[] bytes = expected.getBytes(StandardCharsets.UTF_8);
        int split1 = "prefix ".getBytes(StandardCharsets.UTF_8).length + 1;
        int split2 = split1 + 4;

        Utf8ChunkReader reader = new Utf8ChunkReader(
                new ChunkedInputStream(bytes, split1, split2), 5);

        StringBuilder actual = new StringBuilder();
        String chunk;
        while ((chunk = reader.readChunk()) != null) {
            actual.append(chunk);
        }

        assertEquals(expected, actual.toString(),
                "split UTF-8 characters should decode without corruption");
    }

    private static void assertEquals(String expected, String actual, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(
                    "FAILED " + what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static final class ChunkedInputStream extends InputStream {
        private final byte[] bytes;
        private final int[] limits;
        private int offset;
        private int chunkIndex;

        ChunkedInputStream(byte[] bytes, int... limits) {
            this.bytes = bytes;
            this.limits = limits;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (offset >= bytes.length) { return -1; }
            int chunkEnd = chunkIndex < limits.length ? limits[chunkIndex] : bytes.length;
            int n = Math.min(len, chunkEnd - offset);
            System.arraycopy(bytes, offset, b, off, n);
            offset += n;
            if (offset == chunkEnd && chunkIndex < limits.length) { chunkIndex++; }
            return n;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }
    }
}
