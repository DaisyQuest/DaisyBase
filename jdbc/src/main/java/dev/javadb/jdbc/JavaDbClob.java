package dev.javadb.jdbc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Clob;
import java.sql.SQLException;

class JavaDbClob implements Clob {
    private StringBuilder text;

    JavaDbClob() {
        this("");
    }

    JavaDbClob(String text) {
        this.text = new StringBuilder(text == null ? "" : text);
    }

    @Override
    public long length() throws SQLException {
        ensureOpen();
        return text.length();
    }

    @Override
    public String getSubString(long pos, int length) throws SQLException {
        ensureOpen();
        int start = zeroBasedIndex(pos, false);
        int end = Math.min(text.length(), start + checkLength(length));
        return text.substring(start, end);
    }

    @Override
    public Reader getCharacterStream() throws SQLException {
        ensureOpen();
        return new StringReader(text.toString());
    }

    @Override
    public InputStream getAsciiStream() throws SQLException {
        ensureOpen();
        return new ByteArrayInputStream(text.toString().getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public long position(String searchstr, long start) throws SQLException {
        ensureOpen();
        if (searchstr == null) {
            return -1L;
        }
        int index = text.indexOf(searchstr, zeroBasedSearchIndex(start));
        return index < 0 ? -1L : index + 1L;
    }

    @Override
    public long position(Clob searchstr, long start) throws SQLException {
        ensureOpen();
        if (searchstr == null) {
            return -1L;
        }
        return position(searchstr.getSubString(1L, (int) searchstr.length()), start);
    }

    @Override
    public int setString(long pos, String str) throws SQLException {
        if (str == null) {
            throw new SQLException("str must not be null");
        }
        return setString(pos, str, 0, str.length());
    }

    @Override
    public int setString(long pos, String str, int offset, int len) throws SQLException {
        ensureOpen();
        if (str == null) {
            throw new SQLException("str must not be null");
        }
        if (offset < 0 || len < 0 || offset + len > str.length()) {
            throw new SQLException("offset/len are out of range");
        }
        int start = zeroBasedIndex(pos, true);
        String replacement = str.substring(offset, offset + len);
        int end = Math.min(text.length(), start + len);
        text.replace(start, end, replacement);
        return len;
    }

    @Override
    public OutputStream setAsciiStream(long pos) throws SQLException {
        ensureOpen();
        long checkedPos = pos;
        return new ByteArrayOutputStream() {
            @Override
            public void close() throws java.io.IOException {
                super.close();
                try {
                    setString(checkedPos, toString(StandardCharsets.US_ASCII));
                } catch (SQLException sqlException) {
                    throw new java.io.IOException(sqlException);
                }
            }
        };
    }

    @Override
    public Writer setCharacterStream(long pos) throws SQLException {
        ensureOpen();
        long checkedPos = pos;
        return new StringWriter() {
            @Override
            public void close() throws java.io.IOException {
                super.close();
                try {
                    setString(checkedPos, toString());
                } catch (SQLException sqlException) {
                    throw new java.io.IOException(sqlException);
                }
            }
        };
    }

    @Override
    public void truncate(long len) throws SQLException {
        ensureOpen();
        if (len < 0 || len > text.length()) {
            throw new SQLException("len is out of range");
        }
        text.setLength((int) len);
    }

    @Override
    public void free() {
        text = null;
    }

    @Override
    public Reader getCharacterStream(long pos, long length) throws SQLException {
        return new StringReader(getSubString(pos, checkLength(length)));
    }

    protected final void ensureOpen() throws SQLException {
        if (text == null) {
            throw new SQLException("Clob has been freed");
        }
    }

    private int zeroBasedIndex(long pos, boolean allowAppend) throws SQLException {
        if (pos < 1L) {
            throw new SQLException("Position must be at least 1");
        }
        long max = allowAppend ? text.length() + 1L : text.length();
        if (pos > max) {
            throw new SQLException("Position is out of range");
        }
        return (int) pos - 1;
    }

    private int zeroBasedSearchIndex(long start) throws SQLException {
        if (start < 1L) {
            throw new SQLException("start must be at least 1");
        }
        if (start > text.length() + 1L) {
            return text.length();
        }
        return (int) start - 1;
    }

    private int checkLength(long length) throws SQLException {
        if (length < 0L || length > Integer.MAX_VALUE) {
            throw new SQLException("length is out of range");
        }
        return (int) length;
    }
}
