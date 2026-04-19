package dev.javadb.server;

import dev.javadb.engine.EngineApi;

import java.io.IOException;

public final class JavaDbServer implements AutoCloseable {
    private final DatabaseProtocolServer delegate;

    private JavaDbServer(DatabaseProtocolServer delegate) {
        this.delegate = delegate;
    }

    public static JavaDbServer start(EngineApi.DatabaseEngine engine, int port) throws IOException {
        return new JavaDbServer(DatabaseProtocolServer.start(engine, port));
    }

    public static JavaDbServer start(EngineApi.DatabaseEngine engine, int port,
                                     String user, String password) throws IOException {
        return new JavaDbServer(DatabaseProtocolServer.start(engine, port, user, password));
    }

    public int port() {
        return delegate.port();
    }

    public void await() throws Exception {
        delegate.await();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
