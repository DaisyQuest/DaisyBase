package dev.javadb.jdbc;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;

final class JavaDbSqlXml implements SQLXML {
    private boolean freed;
    private String value = "";
    private StringWriter characterWriter;
    private ByteArrayOutputStream binaryStream;

    @Override
    public void free() {
        freed = true;
        value = null;
        characterWriter = null;
        binaryStream = null;
    }

    @Override
    public InputStream getBinaryStream() throws SQLException {
        return new ByteArrayInputStream(materializedValue().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public OutputStream setBinaryStream() throws SQLException {
        ensureOpen();
        binaryStream = new ByteArrayOutputStream();
        characterWriter = null;
        return binaryStream;
    }

    @Override
    public Reader getCharacterStream() throws SQLException {
        return new StringReader(materializedValue());
    }

    @Override
    public Writer setCharacterStream() throws SQLException {
        ensureOpen();
        characterWriter = new StringWriter();
        binaryStream = null;
        return characterWriter;
    }

    @Override
    public String getString() throws SQLException {
        return materializedValue();
    }

    @Override
    public void setString(String value) throws SQLException {
        ensureOpen();
        this.value = value == null ? "" : value;
        characterWriter = null;
        binaryStream = null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Source> T getSource(Class<T> sourceClass) throws SQLException {
        ensureOpen();
        StreamSource source = new StreamSource(new StringReader(materializedValue()));
        if (sourceClass == null) {
            return (T) source;
        }
        if (sourceClass.isAssignableFrom(StreamSource.class)) {
            return sourceClass.cast(source);
        }
        throw new SQLFeatureNotSupportedException("Only StreamSource SQLXML reads are supported");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Result> T setResult(Class<T> resultClass) throws SQLException {
        ensureOpen();
        if (resultClass == null || resultClass.isAssignableFrom(StreamResult.class)) {
            characterWriter = new StringWriter();
            binaryStream = null;
            StreamResult result = new StreamResult(characterWriter);
            return resultClass == null ? (T) result : resultClass.cast(result);
        }
        throw new SQLFeatureNotSupportedException("Only StreamResult SQLXML writes are supported");
    }

    private void ensureOpen() throws SQLException {
        if (freed) {
            throw new SQLException("SQLXML has been freed");
        }
    }

    private String materializedValue() throws SQLException {
        ensureOpen();
        if (characterWriter != null) {
            value = characterWriter.toString();
        } else if (binaryStream != null) {
            value = binaryStream.toString(StandardCharsets.UTF_8);
        }
        return value == null ? "" : value;
    }
}
