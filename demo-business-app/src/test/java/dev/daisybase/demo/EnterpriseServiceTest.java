package dev.daisybase.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnterpriseServiceTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-04-19T14:30:00Z"),
            ZoneId.of("America/New_York")
    );

    @TempDir
    Path tempDir;

    @Test
    void dashboardReflectsSeededEnterpriseState() {
        Fixture fixture = fixture();
        EnterpriseService service = fixture.service();

        EnterpriseModel.DashboardSnapshot snapshot = service.dashboard();

        assertEquals("Northwind Field Systems", snapshot.enterpriseName());
        assertEquals(4L, snapshot.metrics().activeCustomers());
        assertEquals(2L, snapshot.metrics().openOrders());
        assertEquals(2L, snapshot.metrics().lowStockProducts());
        assertEquals(0, new BigDecimal("1296.00").compareTo(snapshot.metrics().pipelineValue()));
        assertFalse(snapshot.recentOrders().isEmpty());
        assertTrue(snapshot.alerts().stream().anyMatch(alert -> alert.title().contains("Horizon Foods")));
        assertTrue(snapshot.alerts().stream().anyMatch(alert -> alert.title().contains("Inventory pressure")));
    }

    @Test
    void createsBusinessRecordsUsingGeneratedKeys() {
        Fixture fixture = fixture();
        EnterpriseService service = fixture.service();

        EnterpriseModel.CustomerCard customer = service.createCustomer(
                new EnterpriseModel.CreateCustomerRequest("Aster Dynamics", "Strategic", "STABLE", 101L)
        );
        EnterpriseModel.ProductCard product = service.createProduct(
                new EnterpriseModel.CreateProductRequest("AST-EDGE", "Edge Telemetry Pod",
                        new BigDecimal("799.00"), 8, 3)
        );

        assertTrue(customer.id() >= 2000L);
        assertEquals("Aster Dynamics", customer.name());
        assertTrue(product.id() >= 3000L);
        assertEquals("AST-EDGE", product.sku());
        assertTrue(service.customers().stream().anyMatch(card -> card.id() == customer.id()));
        assertTrue(service.products().stream().anyMatch(card -> card.id() == product.id()));
    }

    @Test
    void createsAndFulfillsOrdersThroughJdbcAndStoredRoutines() throws Exception {
        Fixture fixture = fixture();
        EnterpriseService service = fixture.service();

        EnterpriseModel.OrderSummary created = service.createOrder(new EnterpriseModel.CreateOrderRequest(
                201L,
                "Expansion rollout",
                List.of(
                        new EnterpriseModel.OrderLineRequest(301L, 1),
                        new EnterpriseModel.OrderLineRequest(303L, 2)
                )
        ));

        assertTrue(created.id() >= 4000L);
        assertEquals("OPEN", created.status());
        assertEquals(2, created.lines().size());
        assertEquals(0, new BigDecimal("708.00").compareTo(created.totalAmount()));
        assertEquals(10, stockFor(fixture.database(), 301L));
        assertEquals(26, stockFor(fixture.database(), 303L));

        EnterpriseModel.OrderSummary fulfilled = service.fulfillOrder(created.id());

        assertEquals("FULFILLED", fulfilled.status());
        assertEquals("INV-" + created.id() + "-20260419", fulfilled.invoiceCode());
        assertEquals(0, new BigDecimal("720.50").compareTo(invoiceAmountFor(fixture.database(), created.id())));
        assertEquals(1L, invoiceCountFor(fixture.database(), created.id()));

        EnterpriseModel.OrderSummary idempotent = service.fulfillOrder(created.id());
        assertEquals(fulfilled.invoiceCode(), idempotent.invoiceCode());
        assertEquals(1L, invoiceCountFor(fixture.database(), created.id()));
    }

    private Fixture fixture() {
        DemoDatabase database = new DemoDatabase(new DemoAppConfig(
                "jdbc:daisybase:embedded:" + tempDir.resolve("enterprise-demo"),
                "",
                "",
                "Northwind Field Systems"
        ));
        return new Fixture(database, new EnterpriseService(database, FIXED_CLOCK, true));
    }

    private int stockFor(DemoDatabase database, long productId) throws Exception {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT stock_qty FROM products WHERE id = ?")) {
            statement.setLong(1, productId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private BigDecimal invoiceAmountFor(DemoDatabase database, long orderId) throws Exception {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT amount FROM invoices WHERE order_id = ?")) {
            statement.setLong(1, orderId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getBigDecimal(1);
            }
        }
    }

    private long invoiceCountFor(DemoDatabase database, long orderId) throws Exception {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM invoices WHERE order_id = ?")) {
            statement.setLong(1, orderId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getLong(1);
            }
        }
    }

    private record Fixture(DemoDatabase database, EnterpriseService service) {
    }
}
