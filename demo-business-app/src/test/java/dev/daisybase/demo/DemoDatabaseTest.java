package dev.daisybase.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoDatabaseTest {
    @TempDir
    Path tempDir;

    @Test
    void initializesSchemaSeedDataAndRoutines() throws Exception {
        DemoDatabase database = new DemoDatabase(new DemoAppConfig(
                "jdbc:daisybase:embedded:" + tempDir.resolve("demo-db"),
                "",
                "",
                "Test Enterprise"
        ));

        database.initializeIfNeeded();

        try (Connection connection = database.openConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            assertTrue(tableExists(metaData, "employees"));
            assertTrue(tableExists(metaData, "customers"));
            assertTrue(tableExists(metaData, "products"));
            assertTrue(tableExists(metaData, "sales_orders"));
            assertTrue(tableExists(metaData, "sales_order_items"));
            assertTrue(tableExists(metaData, "invoices"));

            try (ResultSet procedures = metaData.getProcedures(null, "public", "fulfill_order")) {
                assertTrue(procedures.next());
            }
            try (ResultSet functions = metaData.getFunctions(null, "public", "billable_total")) {
                assertTrue(functions.next());
            }

            assertEquals(4L, count(connection, "employees"));
            assertEquals(4L, count(connection, "customers"));
            assertEquals(4L, count(connection, "products"));
            assertEquals(3L, count(connection, "sales_orders"));
            assertEquals(4L, count(connection, "sales_order_items"));
            assertEquals(1L, count(connection, "invoices"));
        }
    }

    private boolean tableExists(DatabaseMetaData metaData, String tableName) throws Exception {
        try (ResultSet tables = metaData.getTables(null, "public", tableName, new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    private long count(Connection connection, String tableName) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + tableName);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
