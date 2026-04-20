package dev.daisybase.orm;

public final class DaisyBaseOrmException extends RuntimeException {
    public DaisyBaseOrmException(String message) {
        super(message);
    }

    public DaisyBaseOrmException(String message, Throwable cause) {
        super(message, cause);
    }
}
