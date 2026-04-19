package dev.javadb.jdbc;

import java.sql.NClob;

final class JavaDbNClob extends JavaDbClob implements NClob {
    JavaDbNClob() {
        super();
    }

    JavaDbNClob(String text) {
        super(text);
    }
}
