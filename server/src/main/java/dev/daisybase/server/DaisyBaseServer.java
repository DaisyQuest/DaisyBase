package dev.daisybase.server;

import dev.daisybase.engine.EngineApi;

import java.io.IOException;

public final class DaisyBaseServer implements AutoCloseable {
    private final DatabaseProtocolServer delegate;

    private DaisyBaseServer(DatabaseProtocolServer delegate) {
        this.delegate = delegate;
    }

    public static DaisyBaseServer start(EngineApi.DatabaseEngine engine, int port) throws IOException {
        return new DaisyBaseServer(DatabaseProtocolServer.start(engine, port));
    }

    public static DaisyBaseServer start(EngineApi.DatabaseEngine engine, int port,
                                     String user, String password) throws IOException {
        return new DaisyBaseServer(DatabaseProtocolServer.start(engine, port, user, password));
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
