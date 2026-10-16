package com.flatide.propertee2.value;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Reads the lines of the first {@code limit} bytes of a file one chunk at a time (FILE_LINES,
 * spec v0.26.0). It holds no open file: each chunk is fetched through a {@link Chunks} callback
 * that opens, checks, reads and closes, so a loop suspended between chunks — at a statement
 * switch, a SLEEP, a debugger pause — keeps no descriptor, and nothing needs closing however the
 * loop ends. Memory is one chunk plus the current line. Lines end at \n, \r\n or \r; a leading
 * UTF-8 BOM is dropped; a final line without a terminator still counts.
 */
public final class LineReader {
    public static final int CHUNK = 64 * 1024;

    /** Fetches up to {@code len} bytes at {@code offset} into {@code buf}: the count, or -1 at end of file. */
    @FunctionalInterface
    public interface Chunks {
        int read(long offset, byte[] buf, int len) throws IOException;
    }

    /** Runs once after the last byte has been read (a final consistency check). */
    @FunctionalInterface
    public interface Check {
        void run() throws IOException;
    }

    private final Chunks chunks;
    private final long limit;
    private final int maxLine;
    private final Check atEnd;
    private final byte[] buf;
    private long offset;
    private int pos, lim;
    private boolean eof;
    private byte[] line = new byte[256];
    private int lineLen;
    private long lineNo;
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);

    public LineReader(Chunks chunks, long limit, int maxLine, Check atEnd) {
        this.chunks = chunks;
        this.limit = limit;
        this.maxLine = maxLine;
        this.atEnd = atEnd;
        // A small file needs no 64 KB buffer: many workers streaming small files at once would
        // otherwise hold far more buffer than file.
        this.buf = new byte[(int) Math.max(1, Math.min(CHUNK, limit))];
    }

    /** The next line, or null after the last one. */
    public String next() throws IOException {
        lineLen = 0;
        boolean any = false;
        while (true) {
            if (pos == lim && !refill()) return any ? decode() : null;
            any = true;
            int start = pos;
            while (pos < lim) {
                byte b = buf[pos];
                if (b == '\n' || b == '\r') {
                    append(start, pos - start);
                    pos++;
                    if (b == '\r') {                       // \r\n is one terminator
                        if (pos == lim) refill();
                        if (pos < lim && buf[pos] == '\n') pos++;
                    }
                    return decode();
                }
                pos++;
            }
            append(start, pos - start);
        }
    }

    private boolean refill() throws IOException {
        if (eof) return false;
        if (offset == limit) {
            eof = true;
            pos = lim = 0;
            atEnd.run();
            return false;
        }
        int n = chunks.read(offset, buf, (int) Math.min(buf.length, limit - offset));
        if (n <= 0) throw new IOException("the file was truncated while it was being read");
        offset += n;
        pos = 0;
        lim = n;
        return true;
    }

    private void append(int from, int n) throws IOException {
        if (n == 0) return;
        if (lineLen + n > maxLine) {
            throw new IOException("line " + (lineNo + 1) + " is longer than " + maxLine + " bytes");
        }
        if (lineLen + n > line.length) {
            byte[] grown = new byte[Math.min(maxLine, Math.max(line.length * 2, lineLen + n))];
            System.arraycopy(line, 0, grown, 0, lineLen);
            line = grown;
        }
        System.arraycopy(buf, from, line, lineLen, n);
        lineLen += n;
    }

    private String decode() throws IOException {
        lineNo++;
        int from = 0;
        if (lineNo == 1 && lineLen >= 3 && (line[0] & 0xFF) == 0xEF && (line[1] & 0xFF) == 0xBB
                && (line[2] & 0xFF) == 0xBF) {
            from = 3;                                      // a BOM is not part of the first line
        }
        try {
            return decoder.decode(ByteBuffer.wrap(line, from, lineLen - from)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("line " + lineNo + " is not valid UTF-8");
        }
    }
}
