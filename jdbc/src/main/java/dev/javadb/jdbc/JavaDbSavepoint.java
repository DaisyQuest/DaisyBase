package dev.javadb.jdbc;

import java.sql.Savepoint;

final class JavaDbSavepoint implements Savepoint {
    private final Integer id;
    private final String name;
    private boolean released;

    JavaDbSavepoint(int id, String name) {
        this.id = id;
        this.name = name;
    }

    void markReleased() {
        released = true;
    }

    boolean released() {
        return released;
    }

    @Override
    public int getSavepointId() {
        if (name != null) {
            throw new IllegalStateException("Named savepoints do not have numeric ids");
        }
        return id;
    }

    @Override
    public String getSavepointName() {
        if (name == null) {
            throw new IllegalStateException("Unnamed savepoints do not have a name");
        }
        return name;
    }

    String jdbcName() {
        return name == null ? "SP_" + id : name;
    }
}
