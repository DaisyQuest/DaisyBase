package dev.daisybase.jdbc;

import java.sql.RowId;
import java.util.Arrays;

final class DaisyBaseRowId implements RowId {
    private final byte[] bytes;

    DaisyBaseRowId(byte[] bytes) {
        this.bytes = bytes == null ? new byte[0] : bytes.clone();
    }

    @Override
    public byte[] getBytes() {
        return bytes.clone();
    }

    @Override
    public String toString() {
        return Arrays.toString(bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof RowId other && Arrays.equals(bytes, other.getBytes());
    }
}
