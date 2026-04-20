package dev.daisybase.jdbc;

import java.sql.NClob;

final class DaisyBaseNClob extends DaisyBaseClob implements NClob {
    DaisyBaseNClob() {
        super();
    }

    DaisyBaseNClob(String text) {
        super(text);
    }
}
