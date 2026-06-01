package org.aspose.pdf.engine.io;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Logger;

/**
 * Random-access reader for PDF files. Supports reading from files (via {@link FileChannel})
 * and from byte arrays (for in-memory PDFs). Provides buffered sequential reading as well
 * as seeking to arbitrary positions — essential for PDF parsing where the parser must jump
 * between trailer, xref, and object locations.
 *
 * <p>Reference: ISO 32000-1:2008, §7.5 (File Structure).</p>
 */
public final class RandomAccessReader implements Closeable {

    private static final Logger LOG = Logger.getLogger(RandomAccessReader.class.getName());

    /** Default read-ahead buffer size for file-based reading. */
    private static final int BUFFER_SIZE = 8192;

    // --- File-based fields ---
    private final FileChannel channel;
    private final ByteBuffer buffer;

    // --- Byte-array-based fields ---
    private final byte[] data;

    // --- Common fields ---
    private long position;
    private final long length;
    private boolean closed;

    // ---- Private constructors ----

    private RandomAccessReader(FileChannel channel, long length) {
        this.channel = channel;
        this.buffer = ByteBuffer.allocate(BUFFER_SIZE);
        this.buffer.limit(0); // empty initially
        this.data = null;
        this.position = 0;
        this.length = length;
        LOG.fine(() -> "Created file-based RandomAccessReader, length=" + length);
    }

    private RandomAccessReader(byte[] data) {
        this.channel = null;
        this.buffer = null;
        this.data = data;
        this.position = 0;
        this.length = data.length;
        LOG.fine(() -> "Created byte[]-based RandomAccessReader, length=" + data.length);
    }

    // ---- Factory methods ----

    /**
     * Creates a reader backed by a file.
     *
     * @param file the file to read
     * @return a new reader
     * @throws IOException if the file cannot be opened
     * @throws IllegalArgumentException if file is null
     */
    public static RandomAccessReader fromFile(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        return fromFile(file.toPath());
    }

    /**
     * Creates a reader backed by a file.
     *
     * @param path the path to the file
     * @return a new reader
     * @throws IOException if the file cannot be opened
     * @throws IllegalArgumentException if path is null
     */
    public static RandomAccessReader fromFile(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        FileChannel ch = FileChannel.open(path, StandardOpenOption.READ);
        long size = ch.size();
        return new RandomAccessReader(ch, size);
    }

    /**
     * Creates a reader backed by an in-memory byte array.
     *
     * @param data the byte data
     * @return a new reader
     * @throws IllegalArgumentException if data is null
     */
    public static RandomAccessReader fromBytes(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        return new RandomAccessReader(data.clone());
    }

    /**
     * Creates a reader by reading the entire input stream into memory.
     *
     * @param is the input stream to read (will NOT be closed)
     * @return a new reader
     * @throws IOException if reading fails
     * @throws IllegalArgumentException if is is null
     */
    public static RandomAccessReader fromStream(InputStream is) throws IOException {
        if (is == null) {
            throw new IllegalArgumentException("input stream must not be null");
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[BUFFER_SIZE];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        // Use internal array directly — no clone needed since BAOS is local
        byte[] result = baos.toByteArray();
        RandomAccessReader reader = new RandomAccessReader(new byte[0]);
        // We need to replace the data field — but it's final. Use the normal constructor:
        return new RandomAccessReader(result);
    }

    // ---- Position ----

    /**
     * Returns the current read position.
     *
     * @return position in bytes from the start
     */
    public long getPosition() {
        return position;
    }

    /**
     * Returns the total length of the data source.
     *
     * @return length in bytes
     */
    public long getLength() {
        return length;
    }

    /**
     * Seeks to the given absolute position.
     *
     * @param pos the position to seek to (0-based)
     * @throws IOException if position is out of range or reader is closed
     */
    public void seek(long pos) throws IOException {
        ensureOpen();
        if (pos < 0 || pos > length) {
            throw new IOException("Seek position out of range: " + pos + " (length=" + length + ")");
        }
        this.position = pos;
        if (channel != null) {
            // Invalidate read-ahead buffer
            buffer.limit(0);
        }
        LOG.finer(() -> "seek(" + pos + ")"); // per-IO trace: debug level (Sprint 32 A)
    }

    /**
     * Advances the position by the given number of bytes.
     *
     * @param n number of bytes to skip
     * @throws IOException if the resulting position is out of range
     */
    public void skip(long n) throws IOException {
        seek(position + n);
    }

    // ---- Reading ----

    /**
     * Reads a single byte at the current position and advances by one.
     *
     * @return the byte value (0–255), or -1 if at end of data
     * @throws IOException if the reader is closed or an I/O error occurs
     */
    public int read() throws IOException {
        ensureOpen();
        if (position >= length) {
            return -1;
        }
        if (data != null) {
            return data[(int) position++] & 0xFF;
        }
        // File-based: use buffer
        if (!buffer.hasRemaining()) {
            fillBuffer();
            if (!buffer.hasRemaining()) {
                return -1;
            }
        }
        position++;
        return buffer.get() & 0xFF;
    }

    /**
     * Reads up to {@code len} bytes into the given array.
     *
     * @param buf the destination buffer
     * @param off the offset in buf
     * @param len maximum number of bytes to read
     * @return the number of bytes actually read, or -1 if at EOF
     * @throws IOException if the reader is closed or an I/O error occurs
     */
    public int read(byte[] buf, int off, int len) throws IOException {
        ensureOpen();
        if (buf == null) {
            throw new IllegalArgumentException("buffer must not be null");
        }
        if (off < 0 || len < 0 || off + len > buf.length) {
            throw new IndexOutOfBoundsException("off=" + off + ", len=" + len + ", buf.length=" + buf.length);
        }
        if (len == 0) {
            return 0;
        }
        if (position >= length) {
            return -1;
        }
        int available = (int) Math.min(len, length - position);
        if (data != null) {
            System.arraycopy(data, (int) position, buf, off, available);
            position += available;
            return available;
        }
        // File-based: read from buffer, refilling as needed
        int totalRead = 0;
        while (totalRead < available) {
            if (!buffer.hasRemaining()) {
                fillBuffer();
                if (!buffer.hasRemaining()) {
                    break;
                }
            }
            int toRead = Math.min(buffer.remaining(), available - totalRead);
            buffer.get(buf, off + totalRead, toRead);
            totalRead += toRead;
            position += toRead;
        }
        return totalRead == 0 ? -1 : totalRead;
    }

    /**
     * Reads exactly {@code length} bytes, throwing {@link EOFException} if not enough data is available.
     *
     * @param len the exact number of bytes to read
     * @return a byte array of exactly {@code len} bytes
     * @throws EOFException if fewer than {@code len} bytes remain
     * @throws IOException if the reader is closed or an I/O error occurs
     */
    public byte[] readFully(int len) throws IOException {
        if (len < 0) {
            throw new IllegalArgumentException("length must not be negative: " + len);
        }
        byte[] result = new byte[len];
        int offset = 0;
        while (offset < len) {
            int n = read(result, offset, len - offset);
            if (n == -1) {
                throw new EOFException("Expected " + len + " bytes but only " + offset + " available");
            }
            offset += n;
        }
        return result;
    }

    // ---- Convenience ----

    /**
     * Reads the next byte without advancing the position.
     *
     * @return the byte value (0–255), or -1 if at end of data
     * @throws IOException if the reader is closed or an I/O error occurs
     */
    public int peek() throws IOException {
        ensureOpen();
        if (position >= length) {
            return -1;
        }
        if (data != null) {
            return data[(int) position] & 0xFF;
        }
        // File-based: read one byte, then seek back to restore position
        long savedPos = position;
        int result = read();
        // Seek back — this properly invalidates the read-ahead buffer
        seek(savedPos);
        return result;
    }

    /**
     * Reads an ASCII line (terminated by LF or CR+LF). The line terminator is consumed
     * but not included in the returned string. Returns {@code null} if at EOF.
     *
     * @return the line as a string, or null if at EOF
     * @throws IOException if the reader is closed or an I/O error occurs
     */
    public String readLine() throws IOException {
        ensureOpen();
        if (position >= length) {
            return null;
        }
        StringBuilder sb = new StringBuilder(128);
        int ch;
        while ((ch = read()) != -1) {
            if (ch == '\n') {
                break;
            }
            if (ch == '\r') {
                // Check for CR+LF
                int next = peek();
                if (next == '\n') {
                    read(); // consume LF
                }
                break;
            }
            sb.append((char) ch);
        }
        // If we read nothing and hit EOF, return the empty string only if we had data
        if (sb.length() == 0 && ch == -1) {
            return null;
        }
        return sb.toString();
    }

    /**
     * Returns {@code true} if the current position is at or past the end of data.
     *
     * @return true if at EOF
     */
    public boolean isEOF() {
        return position >= length;
    }

    /**
     * Searches backward from {@code startFrom} for the given byte pattern.
     * Useful for finding {@code %%EOF} and {@code startxref} near the end of a PDF file.
     *
     * @param pattern the byte sequence to search for
     * @param startFrom the position to start searching backward from
     * @return the position of the first (rightmost) occurrence, or -1 if not found
     * @throws IOException if an I/O error occurs
     */
    public long findBackward(byte[] pattern, long startFrom) throws IOException {
        ensureOpen();
        if (pattern == null || pattern.length == 0) {
            throw new IllegalArgumentException("pattern must not be null or empty");
        }
        if (startFrom < 0) {
            return -1;
        }
        long searchEnd = Math.min(startFrom, length - 1);

        // Read blocks backward
        int blockSize = 1024;
        long blockStart = Math.max(0, searchEnd - blockSize + 1);
        // We need to handle the case where pattern spans block boundaries,
        // so we overlap blocks by pattern.length - 1 bytes.
        int overlap = pattern.length - 1;

        while (true) {
            long readStart = Math.max(0, blockStart);
            int readLen = (int) Math.min(blockSize + overlap, length - readStart);
            if (readLen <= 0) {
                break;
            }
            // Limit read to available data
            readLen = (int) Math.min(readLen, length - readStart);

            seek(readStart);
            byte[] block = readFully(readLen);

            // Search backward within this block
            for (int i = Math.min(block.length - pattern.length, (int)(searchEnd - readStart)); i >= 0; i--) {
                if (matches(block, i, pattern)) {
                    return readStart + i;
                }
            }

            if (readStart == 0) {
                break;
            }
            blockStart = Math.max(0, blockStart - blockSize);
            searchEnd = readStart + overlap - 1;
        }

        return -1;
    }

    /**
     * Searches forward from the given position for the first occurrence of the pattern.
     *
     * @param pattern the byte pattern to search for
     * @param startFrom the position to start searching from
     * @return the position of the first match, or -1 if not found
     * @throws IOException if an I/O error occurs
     */
    public long findForward(byte[] pattern, long startFrom) throws IOException {
        ensureOpen();
        if (pattern == null || pattern.length == 0) {
            throw new IllegalArgumentException("pattern must not be null or empty");
        }
        if (startFrom < 0 || startFrom >= length) {
            return -1;
        }

        int blockSize = 4096;
        int overlap = pattern.length - 1;
        long pos = startFrom;

        while (pos < length) {
            int readLen = (int) Math.min(blockSize, length - pos);
            seek(pos);
            byte[] block = readFully(readLen);

            int searchLimit = (pos + blockSize < length) ? block.length - overlap : block.length - pattern.length + 1;
            for (int i = 0; i < searchLimit; i++) {
                if (i + pattern.length <= block.length && matches(block, i, pattern)) {
                    return pos + i;
                }
            }

            pos += blockSize - overlap;
        }

        return -1;
    }

    /**
     * Closes this reader and releases any underlying resources.
     *
     * @throws IOException if an I/O error occurs
     */
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            if (channel != null) {
                channel.close();
            }
            LOG.fine("RandomAccessReader closed");
        }
    }

    // ---- Internal helpers ----

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("RandomAccessReader is closed");
        }
    }

    /**
     * Fills the read-ahead buffer from the file channel at the current position.
     */
    private void fillBuffer() throws IOException {
        buffer.clear();
        channel.position(position);
        int n = channel.read(buffer);
        if (n == -1) {
            buffer.limit(0);
        } else {
            buffer.flip();
        }
    }

    /**
     * Checks if the block contains the pattern at the given offset.
     */
    private static boolean matches(byte[] block, int offset, byte[] pattern) {
        for (int j = 0; j < pattern.length; j++) {
            if (block[offset + j] != pattern[j]) {
                return false;
            }
        }
        return true;
    }
}
