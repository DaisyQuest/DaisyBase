package dev.javadb.jdbc;

import dev.javadb.engine.EmbeddedDatabaseEngine;
import dev.javadb.engine.EngineApi;
import dev.javadb.server.JavaDbServer;
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

class JavaDbXADataSourceTest {
    @BeforeAll
    static void loadDriver() throws Exception {
        Class.forName(JavaDbDriver.class.getName());
    }

    @TempDir
    Path tempDir;

    @Test
    void embeddedXaDataSourceSupportsPrepareCommitAndRollback() throws Exception {
        JavaDbXADataSource dataSource = new JavaDbXADataSource();
        dataSource.setUrl("jdbc:javadb:embedded:" + tempDir);

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
    void remoteXaDataSourceSupportsAuthenticatedSingleBranchTransactions() throws Exception {
        try (EngineApi.DatabaseEngine engine = EmbeddedDatabaseEngine.open(tempDir);
             JavaDbServer server = JavaDbServer.start(engine, 0, "app", "secret")) {
            String url = "jdbc:javadb:remote://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.port();
            JavaDbXADataSource dataSource = new JavaDbXADataSource();
            dataSource.setUrl(url);
            dataSource.setUser("app");
            dataSource.setPassword("secret");

            javax.sql.XAConnection bootstrapXa = dataSource.getXAConnection();
            try (Connection bootstrap = bootstrapXa.getConnection();
                 Statement statement = bootstrap.createStatement()) {
                statement.execute("CREATE TABLE remote_xa_logs (id INT PRIMARY KEY, note TEXT NOT NULL);");
            } finally {
                bootstrapXa.close();
            }

            XAConnection xaConnection = dataSource.getXAConnection();
            try {
                XAResource resource = xaConnection.getXAResource();
                try (Connection connection = xaConnection.getConnection();
                     PreparedStatement insert = connection.prepareStatement("INSERT INTO remote_xa_logs VALUES (?, ?)")) {
                    Xid xid = new TestXid(3, new byte[]{3}, new byte[]{3});
                    resource.start(xid, XAResource.TMNOFLAGS);
                    insert.setInt(1, 1);
                    insert.setString(2, "remote");
                    insert.executeUpdate();
                    resource.end(xid, XAResource.TMSUCCESS);
                    resource.commit(xid, true);
                }
            } finally {
                xaConnection.close();
            }

            xaConnection = dataSource.getXAConnection();
            try (Connection connection = xaConnection.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM remote_xa_logs")) {
                assertTrue(resultSet.next());
                assertEquals(1L, resultSet.getLong(1));
            } finally {
                xaConnection.close();
            }
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
