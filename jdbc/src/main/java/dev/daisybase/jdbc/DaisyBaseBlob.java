package dev.daisybase.jdbc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Blob;
import java.sql.SQLException;
import java.util.Arrays;

final class DaisyBaseBlob implements Blob {
    private byte[] bytes = new byte[0];

    @Override
    public long length() throws SQLException {
        ensureOpen();
        return bytes.length;
    }

    @Override
    public byte[] getBytes(long pos, int length) throws SQLException {
        ensureOpen();
        int start = zeroBasedIndex(pos, false);
        int count = checkedLength(length);
        int end = Math.min(bytes.length, start + count);
        return Arrays.copyOfRange(bytes, start, end);
    }

    @Override
    public InputStream getBinaryStream() throws SQLException {
        ensureOpen();
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public long position(byte[] pattern, long start) throws SQLException {
        ensureOpen();
        if (pattern == null || pattern.length == 0) {
            return -1L;
        }
        int startIndex = zeroBasedSearchIndex(start);
        for (int index = startIndex; index <= bytes.length - pattern.length; index++) {
            boolean matches = true;
            for (int patternIndex = 0; patternIndex < pattern.length; patternIndex++) {
                if (bytes[index + patternIndex] != pattern[patternIndex]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return index + 1L;
            }
        }
        return -1L;
    }

    @Override
    public long position(Blob pattern, long start) throws SQLException {
        ensureOpen();
        if (pattern == null) {
            return -1L;
        }
        return position(pattern.getBytes(1L, (int) pattern.length()), start);
    }

    @Override
    public int setBytes(long pos, byte[] bytes) throws SQLException {
        if (bytes == null) {
            throw new SQLException("bytes must not be null");
        }
        return setBytes(pos, bytes, 0, bytes.length);
    }

    @Override
    public int setBytes(long pos, byte[] bytes, int offset, int len) throws SQLException {
        ensureOpen();
        if (bytes == null) {
            throw new SQLException("bytes must not be null");
        }
        if (offset < 0 || len < 0 || offset + len > bytes.length) {
            throw new SQLException("offset/len are out of range");
        }
        int start = zeroBasedIndex(pos, true);
        int requiredLength = start + len;
        if (requiredLength > this.bytes.length) {
            this.bytes = Arrays.copyOf(this.bytes, requiredLength);
        }
        System.arraycopy(bytes, offset, this.bytes, start, len);
        return len;
    }

    @Override
    public OutputStream setBinaryStream(long pos) throws SQLException {
        ensureOpen();
        long checkedPos = pos;
        return new ByteArrayOutputStream() {
            @Override
            public void close() throws java.io.IOException {
                super.close();
                try {
                    setBytes(checkedPos, toByteArray());
                } catch (SQLException sqlException) {
                    throw new java.io.IOException(sqlException);
                }
            }
        };
    }

    @Override
    public void truncate(long len) throws SQLException {
        ensureOpen();
        if (len < 0 || len > bytes.length) {
            throw new SQLException("len is out of range");
        }
        bytes = Arrays.copyOf(bytes, (int) len);
    }

    @Override
    public void free() {
        bytes = null;
    }

    @Override
    public InputStream getBinaryStream(long pos, long length) throws SQLException {
        return new ByteArrayInputStream(getBytes(pos, checkedLength(length)));
    }

    private void ensureOpen() throws SQLException {
        if (bytes == null) {
            throw new SQLException("Blob has been freed");
        }
    }

    private int zeroBasedIndex(long pos, boolean allowAppend) throws SQLException {
        if (pos < 1L) {
            throw new SQLException("Position must be at least 1");
        }
        long max = allowAppend ? bytes.length + 1L : bytes.length;
        if (pos > max) {
            throw new SQLException("Position is out of range");
        }
        return (int) pos - 1;
    }

    private int zeroBasedSearchIndex(long start) throws SQLException {
        if (start < 1L) {
            throw new SQLException("start must be at least 1");
        }
        if (start > bytes.length + 1L) {
            return bytes.length;
        }
        return (int) start - 1;
    }

    private int checkedLength(long length) throws SQLException {
        if (length < 0L || length > Integer.MAX_VALUE) {
            throw new SQLException("length is out of range");
        }
        return (int) length;
    }
}
