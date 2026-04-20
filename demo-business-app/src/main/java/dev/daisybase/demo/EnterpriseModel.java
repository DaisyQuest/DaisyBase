package dev.daisybase.demo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class EnterpriseModel {
    private EnterpriseModel() {
    }

    public record DashboardSnapshot(String enterpriseName, Metrics metrics, List<Alert> alerts,
                                    List<OrderSummary> recentOrders) {
    }

    public record Metrics(long activeCustomers, long openOrders, long lowStockProducts, BigDecimal pipelineValue) {
    }

    public record Alert(String title, String body, String tone) {
    }

    public record EmployeeCard(long id, String fullName, String title, String status, int capacity,
                               BigDecimal dailyBillable) {
    }

    public record CustomerCard(long id, String name, String segment, String health, String accountOwner) {
    }

    public record ProductCard(long id, String sku, String name, BigDecimal unitPrice, int stockQty,
                              int reorderPoint, String stockState) {
    }

    public record OrderLineView(long id, int lineNo, String productName, int quantity,
                                BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    public record OrderSummary(long id, String customerName, String status, LocalDate openedOn,
                               LocalDate dueOn, BigDecimal totalAmount, String invoiceCode,
                               List<OrderLineView> lines) {
    }

    public record CreateCustomerRequest(String name, String segment, String health, Long accountOwnerId) {
    }

    public record CreateProductRequest(String sku, String name, BigDecimal unitPrice, Integer stockQty,
                                       Integer reorderPoint) {
    }

    public record OrderLineRequest(Long productId, Integer quantity) {
    }

    public record CreateOrderRequest(Long customerId, String notes, List<OrderLineRequest> lines) {
    }
}
