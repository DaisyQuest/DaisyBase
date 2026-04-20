package dev.daisybase.jdbc;

import dev.daisybase.engine.EmbeddedDatabaseEngine;
import dev.daisybase.engine.EngineApi;
import dev.daisybase.server.DaisyBaseServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.net.InetAddress;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaisyBaseXADataSourceTest {
    @BeforeAll
    static void loadDriver() throws Exception {
        Class.forName(DaisyBaseDriver.class.getName());
    }

    @TempDir
    Path tempDir;

    @Test
    void embeddedXaDataSourceSupportsPrepareCommitAndRollback() throws Exception {
        DaisyBaseXADataSource dataSource = new DaisyBaseXADataSource();
        dataSource.setUrl("jdbc:daisybase:embedded:" + tempDir);

        javax.sql.XAConnection bootstrapXa = dataSource.getXAConnection();
        try (Connection bootstrap = bootstrapXa.getConnection();
             Statement statement = bootstrap.createStatement()) {
            statement.execute("CREATE TABLE xa_logs (id INT PRIMARY KEY, note TEXT NOT NULL);");
        } finally {
            bootstrapXa.close();
        }

        XAConnection xaConnection = dataSource.getXAConnection();
        try {
            XAResource resource = xaConnection.getXAResource();
            try (Connection connection = xaConnection.getConnection();
                 PreparedStatement insert = connection.prepareStatement("INSERT INTO xa_logs VALUES (?, ?)")) {
                Xid xid = new TestXid(1, new byte[]{1}, new byte[]{1});
                resource.start(xid, XAResource.TMNOFLAGS);
                insert.setInt(1, 1);
                insert.setString(2, "commit");
                insert.executeUpdate();
                resource.end(xid, XAResource.TMSUCCESS);
                assertEquals(XAResource.XA_OK, resource.prepare(xid));
                resource.commit(xid, false);
            }
        } finally {
            xaConnection.close();
        }

        xaConnection = dataSource.getXAConnection();
        try {
            XAResource resource = xaConnection.getXAResource();
            try (Connection connection = xaConnection.getConnection();
                 PreparedStatement insert = connection.prepareStatement("INSERT INTO xa_logs VALUES (?, ?)")) {
                Xid xid = new TestXid(2, new byte[]{2}, new byte[]{2});
                resource.start(xid, XAResource.TMNOFLAGS);
                insert.setInt(1, 2);
                insert.setString(2, "rollback");
                insert.executeUpdate();
                resource.end(xid, XAResource.TMFAIL);
                resource.rollback(xid);
            }
        } finally {
            xaConnection.close();
        }

        xaConnection = dataSource.getXAConnection();
        try (Connection connection = xaConnection.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT id, note FROM xa_logs ORDER BY id")) {
            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getInt(1));
            assertEquals("commit", resultSet.getString(2));
            assertTrue(!resultSet.next());
        } finally {
            xaConnection.close();
        }
    }

    @Test
    void embeddedXaRecoverySurvivesConnectionCloseAndRecover() throws Exception {
        DaisyBaseXADataSource dataSource = new DaisyBaseXADataSource();
        dataSource.setUrl("jdbc:daisybase:embedded:" + tempDir);

        javax.sql.XAConnection bootstrapXa = dataSource.getXAConnection();
        try (Connection bootstrap = bootstrapXa.getConnection();
             Statement statement = bootstrap.createStatement()) {
            statement.execute("CREATE TABLE xa_recover (id INT PRIMARY KEY, note TEXT NOT NULL);");
        } finally {
            bootstrapXa.close();
        }

        Xid xid = new TestXid(9, new byte[]{9}, new byte[]{1});
        XAConnection xaConnection = dataSource.getXAConnection();
        try {
            XAResource resource = xaConnection.getXAResource();
            try (Connection connection = xaConnection.getConnection();
                 PreparedStatement insert = connection.prepareStatement("INSERT INTO xa_recover VALUES (?, ?)")) {
                resource.start(xid, XAResource.TMNOFLAGS);
                insert.setInt(1, 1);
                insert.setString(2, "prepared");
                insert.executeUpdate();
                resource.end(xid, XAResource.TMSUCCESS);
                assertEquals(XAResource.XA_OK, resource.prepare(xid));
            }
        } finally {
            xaConnection.close();
        }

        xaConnection = dataSource.getXAConnection();
        try {
            XAResource resource = xaConnection.getXAResource();
            Xid[] recovered = resource.recover(XAResource.TMSTARTRSCAN);
            assertEquals(1, recovered.length);
            resource.commit(recovered[0], false);
        } finally {
            xaConnection.close();
        }

        xaConnection = dataSource.getXAConnection();
        try (Connection connection = xaConnection.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT note FROM xa_recover")) {
            assertTrue(resultSet.next());
            assertEquals("prepared", resultSet.getString(1));
        } finally {
            xaConnection.close();
        }
    }

    @Test
    void remoteXaDataSourceSupportsAuthenticatedRecoveryAcrossRestart() throws Exception {
        String url;
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session session = engine.openSession()) {
            session.execute("CREATE USER app IDENTIFIED BY 'secret';");
            session.execute("CREATE TABLE remote_xa_logs (id INT PRIMARY KEY, note TEXT NOT NULL);");
            session.execute("GRANT INSERT ON TABLE public.remote_xa_logs TO app;");
        }

        DaisyBaseXADataSource dataSource = new DaisyBaseXADataSource();
        dataSource.setUser("app");
        dataSource.setPassword("secret");
        Xid xid = new TestXid(3, new byte[]{3}, new byte[]{3});

        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             DaisyBaseServer server = DaisyBaseServer.start(engine, 0)) {
            url = "jdbc:daisybase:remote://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.port();
            dataSource.setUrl(url);

            XAConnection xaConnection = dataSource.getXAConnection();
            try {
                XAResource resource = xaConnection.getXAResource();
                try (Connection connection = xaConnection.getConnection();
                     PreparedStatement insert = connection.prepareStatement("INSERT INTO remote_xa_logs VALUES (?, ?)")) {
                    resource.start(xid, XAResource.TMNOFLAGS);
                    insert.setInt(1, 1);
                    insert.setString(2, "remote");
                    insert.executeUpdate();
                    resource.end(xid, XAResource.TMSUCCESS);
                    assertEquals(XAResource.XA_OK, resource.prepare(xid));
                }
            } finally {
                xaConnection.close();
            }
        }

        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             DaisyBaseServer server = DaisyBaseServer.start(engine, 0)) {
            dataSource.setUrl("jdbc:daisybase:remote://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.port());
            XAConnection xaConnection = dataSource.getXAConnection();
            try {
                XAResource resource = xaConnection.getXAResource();
                Xid[] recovered = resource.recover(XAResource.TMSTARTRSCAN);
                assertEquals(1, recovered.length);
                resource.commit(recovered[0], false);
            } finally {
                xaConnection.close();
            }
        }

        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             EngineApi.Session verify = engine.openSession()) {
            EngineApi.StatementResult result = verify.execute("SELECT COUNT(*) FROM remote_xa_logs").statements().getFirst();
            assertEquals(1L, result.batch().rows().getFirst().get(0).asLong());
        }
    }

    private record TestXid(int formatId, byte[] globalId, byte[] branchId) implements Xid {
        @Override
        public int getFormatId() {
            return formatId;
        }

        @Override
        public byte[] getGlobalTransactionId() {
            return globalId.clone();
        }

        @Override
        public byte[] getBranchQualifier() {
            return branchId.clone();
        }
    }
}
