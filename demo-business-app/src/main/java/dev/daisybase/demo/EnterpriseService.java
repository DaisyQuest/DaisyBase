package dev.daisybase.demo;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static dev.daisybase.demo.EnterpriseModel.Alert;
import static dev.daisybase.demo.EnterpriseModel.CreateCustomerRequest;
import static dev.daisybase.demo.EnterpriseModel.CreateOrderRequest;
import static dev.daisybase.demo.EnterpriseModel.CreateProductRequest;
import static dev.daisybase.demo.EnterpriseModel.CustomerCard;
import static dev.daisybase.demo.EnterpriseModel.DashboardSnapshot;
import static dev.daisybase.demo.EnterpriseModel.EmployeeCard;
import static dev.daisybase.demo.EnterpriseModel.Metrics;
import static dev.daisybase.demo.EnterpriseModel.OrderLineRequest;
import static dev.daisybase.demo.EnterpriseModel.OrderLineView;
import static dev.daisybase.demo.EnterpriseModel.OrderSummary;
import static dev.daisybase.demo.EnterpriseModel.ProductCard;

@Dependent
public class EnterpriseService {
    private static final BigDecimal ZERO_MONEY = new BigDecimal("0.00");

    private final DemoDatabase database;
    private final Clock clock;

    @Inject
    public EnterpriseService(DemoDatabase database) {
        this(database, Clock.systemDefaultZone(), true);
    }

    EnterpriseService(DemoDatabase database, Clock clock, boolean initialize) {
        this.database = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (initialize) {
            database.initializeIfNeeded();
        }
    }

    public DashboardSnapshot dashboard() {
        List<CustomerCard> customers = customers();
        List<ProductCard> products = products();
        List<OrderSummary> orders = orders();

        long openOrders = orders.stream()
                .filter(order -> "OPEN".equalsIgnoreCase(order.status()))
                .count();
        long lowStockProducts = products.stream()
                .filter(product -> !"READY".equals(product.stockState()))
                .count();
        BigDecimal pipelineValue = orders.stream()
                .filter(order -> "OPEN".equalsIgnoreCase(order.status()))
                .map(OrderSummary::totalAmount)
                .reduce(ZERO_MONEY, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        List<Alert> alerts = new ArrayList<>();
        products.stream()
                .filter(product -> "CRITICAL".equals(product.stockState()) || "LOW".equals(product.stockState()))
                .forEach(product -> alerts.add(new Alert(
                        "Inventory pressure: " + product.name(),
                        "SKU " + product.sku() + " is at " + product.stockQty() + " units against reorder point "
                                + product.reorderPoint() + ".",
                        "warn"
                )));
        customers.stream()
                .filter(customer -> "AT_RISK".equalsIgnoreCase(customer.health()))
                .forEach(customer -> alerts.add(new Alert(
                        "Customer health risk: " + customer.name(),
                        customer.segment() + " account owned by " + customer.accountOwner()
                                + " requires intervention.",
                        "risk"
                )));
        LocalDate today = LocalDate.now(clock);
        orders.stream()
                .filter(order -> "OPEN".equalsIgnoreCase(order.status()))
                .filter(order -> order.dueOn().isBefore(today))
                .forEach(order -> alerts.add(new Alert(
                        "Overdue order #" + order.id(),
                        order.customerName() + " is past due since " + order.dueOn() + ".",
                        "risk"
                )));
        if (alerts.isEmpty()) {
            alerts.add(new Alert("Operations stable", "No critical issues are active in the current snapshot.", "info"));
        }

        List<OrderSummary> recentOrders = orders.stream()
                .sorted(Comparator.comparing(OrderSummary::openedOn).thenComparing(OrderSummary::id).reversed())
                .limit(6)
                .toList();

        return new DashboardSnapshot(
                database.config().enterpriseName(),
                new Metrics(customers.size(), openOrders, lowStockProducts, pipelineValue),
                List.copyOf(alerts),
                recentOrders
        );
    }

    public List<EmployeeCard> employees() {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, full_name, title, status, capacity, daily_billable FROM employees");
             ResultSet resultSet = statement.executeQuery()) {
            List<EmployeeCard> employees = new ArrayList<>();
            while (resultSet.next()) {
                employees.add(new EmployeeCard(
                        resultSet.getLong("id"),
                        resultSet.getString("full_name"),
                        resultSet.getString("title"),
                        resultSet.getString("status"),
                        resultSet.getInt("capacity"),
                        money(resultSet.getBigDecimal("daily_billable"))
                ));
            }
            employees.sort(Comparator.comparing(EmployeeCard::fullName));
            return List.copyOf(employees);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load employees", exception);
        }
    }

    public List<CustomerCard> customers() {
        try (Connection connection = database.openConnection()) {
            return loadCustomers(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load customers", exception);
        }
    }

    public CustomerCard createCustomer(CreateCustomerRequest request) {
        requireText(request.name(), "Customer name is required.");
        requireText(request.segment(), "Customer segment is required.");
        requireText(request.health(), "Customer health is required.");
        if (request.accountOwnerId() == null) {
            throw new IllegalArgumentException("Account owner is required.");
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                ensureEmployeeExists(connection, request.accountOwnerId());
                long customerId;
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO customers (name, segment, health, account_owner_id) VALUES (?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    insert.setString(1, request.name().trim());
                    insert.setString(2, request.segment().trim());
                    insert.setString(3, request.health().trim());
                    insert.setLong(4, request.accountOwnerId());
                    insert.executeUpdate();
                    customerId = generatedKey(insert);
                }
                connection.commit();
                return loadCustomer(connection, customerId);
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to create customer", exception);
        }
    }

    public List<ProductCard> products() {
        try (Connection connection = database.openConnection()) {
            return loadProducts(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load products", exception);
        }
    }

    public ProductCard createProduct(CreateProductRequest request) {
        requireText(request.sku(), "Product SKU is required.");
        requireText(request.name(), "Product name is required.");
        if (request.unitPrice() == null || request.unitPrice().signum() <= 0) {
            throw new IllegalArgumentException("Product unit price must be positive.");
        }
        if (request.stockQty() == null || request.stockQty() < 0) {
            throw new IllegalArgumentException("Stock quantity must be zero or greater.");
        }
        if (request.reorderPoint() == null || request.reorderPoint() < 0) {
            throw new IllegalArgumentException("Reorder point must be zero or greater.");
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                long productId;
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO products (sku, name, unit_price, stock_qty, reorder_point) VALUES (?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    insert.setString(1, request.sku().trim());
                    insert.setString(2, request.name().trim());
                    insert.setBigDecimal(3, money(request.unitPrice()));
                    insert.setInt(4, request.stockQty());
                    insert.setInt(5, request.reorderPoint());
                    insert.executeUpdate();
                    productId = generatedKey(insert);
                }
                connection.commit();
                return loadProduct(connection, productId);
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to create product", exception);
        }
    }

    public List<OrderSummary> orders() {
        try (Connection connection = database.openConnection()) {
            return loadOrders(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load orders", exception);
        }
    }

    public OrderSummary createOrder(CreateOrderRequest request) {
        if (request.customerId() == null) {
            throw new IllegalArgumentException("Customer is required.");
        }
        List<OrderLineRequest> lines = request.lines() == null ? List.of() : request.lines().stream()
                .filter(Objects::nonNull)
                .toList();
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("At least one order line is required.");
        }

        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                loadCustomer(connection, request.customerId());
                List<ProductSelection> selections = resolveSelections(connection, lines);
                BigDecimal totalAmount = selections.stream()
                        .map(ProductSelection::lineTotal)
                        .reduce(ZERO_MONEY, BigDecimal::add)
                        .setScale(2, RoundingMode.HALF_UP);

                LocalDate today = LocalDate.now(clock);
                long orderId;
                try (PreparedStatement insertOrder = connection.prepareStatement(
                        "INSERT INTO sales_orders (customer_id, status, opened_on, due_on, total_amount, notes) "
                                + "VALUES (?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    insertOrder.setLong(1, request.customerId());
                    insertOrder.setString(2, "OPEN");
                    insertOrder.setDate(3, Date.valueOf(today));
                    insertOrder.setDate(4, Date.valueOf(today.plusDays(7)));
                    insertOrder.setBigDecimal(5, totalAmount);
                    if (request.notes() == null || request.notes().isBlank()) {
                        insertOrder.setNull(6, Types.VARCHAR);
                    } else {
                        insertOrder.setString(6, request.notes().trim());
                    }
                    insertOrder.executeUpdate();
                    orderId = generatedKey(insertOrder);
                }

                try (PreparedStatement insertLine = connection.prepareStatement(
                        "INSERT INTO sales_order_items (order_id, line_no, product_id, quantity, unit_price, line_total) "
                                + "VALUES (?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                     PreparedStatement updateStock = connection.prepareStatement(
                             "UPDATE products SET stock_qty = ? WHERE id = ?")) {
                    int lineNo = 1;
                    for (ProductSelection selection : selections) {
                        insertLine.setLong(1, orderId);
                        insertLine.setInt(2, lineNo++);
                        insertLine.setLong(3, selection.productId());
                        insertLine.setInt(4, selection.quantity());
                        insertLine.setBigDecimal(5, selection.unitPrice());
                        insertLine.setBigDecimal(6, selection.lineTotal());
                        insertLine.executeUpdate();
                        updateStock.setInt(1, selection.remainingStock());
                        updateStock.setLong(2, selection.productId());
                        updateStock.executeUpdate();
                    }
                }

                connection.commit();
                return loadOrder(connection, orderId);
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to create order", exception);
        }
    }

    public OrderSummary fulfillOrder(long orderId) {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                OrderState state = loadOrderState(connection, orderId);
                if ("FULFILLED".equalsIgnoreCase(state.status()) && state.invoiceCode() != null) {
                    connection.rollback();
                    connection.setAutoCommit(true);
                    return loadOrder(connection, orderId);
                }

                try (CallableStatement statement = connection.prepareCall("{call public.fulfill_order(?, ?)}")) {
                    statement.setLong(1, orderId);
                    statement.registerOutParameter(2, Types.VARCHAR);
                    statement.execute();
                }

                BigDecimal billedAmount;
                try (CallableStatement statement = connection.prepareCall("{? = call public.billable_total(?)}")) {
                    statement.registerOutParameter(1, Types.DECIMAL);
                    statement.setBigDecimal(2, state.totalAmount());
                    statement.execute();
                    billedAmount = money(statement.getBigDecimal(1));
                }

                if (state.invoiceCode() == null) {
                    try (PreparedStatement insertInvoice = connection.prepareStatement(
                            "INSERT INTO invoices (order_id, invoice_code, issued_on, amount, state) VALUES (?, ?, ?, ?, ?)")) {
                        insertInvoice.setLong(1, orderId);
                        insertInvoice.setString(2, invoiceCode(orderId));
                        insertInvoice.setDate(3, Date.valueOf(LocalDate.now(clock)));
                        insertInvoice.setBigDecimal(4, billedAmount);
                        insertInvoice.setString(5, "POSTED");
                        insertInvoice.executeUpdate();
                    }
                }

                connection.commit();
                return loadOrder(connection, orderId);
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to fulfill order " + orderId, exception);
        }
    }

    private List<CustomerCard> loadCustomers(Connection connection) throws SQLException {
        Map<Long, String> ownerNames = loadEmployeeNames(connection);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, name, segment, health, account_owner_id FROM customers");
             ResultSet resultSet = statement.executeQuery()) {
            List<CustomerCard> customers = new ArrayList<>();
            while (resultSet.next()) {
                long ownerId = resultSet.getLong("account_owner_id");
                customers.add(new CustomerCard(
                        resultSet.getLong("id"),
                        resultSet.getString("name"),
                        resultSet.getString("segment"),
                        resultSet.getString("health"),
                        ownerNames.getOrDefault(ownerId, "Employee #" + ownerId)
                ));
            }
            customers.sort(Comparator.comparing(CustomerCard::name));
            return List.copyOf(customers);
        }
    }

    private CustomerCard loadCustomer(Connection connection, long customerId) throws SQLException {
        Map<Long, String> ownerNames = loadEmployeeNames(connection);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, name, segment, health, account_owner_id FROM customers WHERE id = ?")) {
            statement.setLong(1, customerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("Customer " + customerId + " does not exist.");
                }
                long ownerId = resultSet.getLong("account_owner_id");
                return new CustomerCard(
                        resultSet.getLong("id"),
                        resultSet.getString("name"),
                        resultSet.getString("segment"),
                        resultSet.getString("health"),
                        ownerNames.getOrDefault(ownerId, "Employee #" + ownerId)
                );
            }
        }
    }

    private List<ProductCard> loadProducts(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, sku, name, unit_price, stock_qty, reorder_point FROM products");
             ResultSet resultSet = statement.executeQuery()) {
            List<ProductCard> products = new ArrayList<>();
            while (resultSet.next()) {
                int stockQty = resultSet.getInt("stock_qty");
                int reorderPoint = resultSet.getInt("reorder_point");
                products.add(new ProductCard(
                        resultSet.getLong("id"),
                        resultSet.getString("sku"),
                        resultSet.getString("name"),
                        money(resultSet.getBigDecimal("unit_price")),
                        stockQty,
                        reorderPoint,
                        stockState(stockQty, reorderPoint)
                ));
            }
            products.sort(Comparator
                    .comparingInt((ProductCard product) -> switch (product.stockState()) {
                        case "CRITICAL" -> 0;
                        case "LOW" -> 1;
                        default -> 2;
                    })
                    .thenComparing(ProductCard::name));
            return List.copyOf(products);
        }
    }

    private ProductCard loadProduct(Connection connection, long productId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, sku, name, unit_price, stock_qty, reorder_point FROM products WHERE id = ?")) {
            statement.setLong(1, productId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("Product " + productId + " does not exist.");
                }
                int stockQty = resultSet.getInt("stock_qty");
                int reorderPoint = resultSet.getInt("reorder_point");
                return new ProductCard(
                        resultSet.getLong("id"),
                        resultSet.getString("sku"),
                        resultSet.getString("name"),
                        money(resultSet.getBigDecimal("unit_price")),
                        stockQty,
                        reorderPoint,
                        stockState(stockQty, reorderPoint)
                );
            }
        }
    }

    private List<OrderSummary> loadOrders(Connection connection) throws SQLException {
        Map<Long, String> customerNames = loadCustomerNames(connection);
        Map<Long, String> productNames = loadProductNames(connection);
        Map<Long, String> invoiceCodes = loadInvoiceCodes(connection);
        Map<Long, List<OrderLineView>> linesByOrderId = loadOrderLines(connection, productNames);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, customer_id, status, opened_on, due_on, total_amount FROM sales_orders");
             ResultSet resultSet = statement.executeQuery()) {
            List<OrderSummary> orders = new ArrayList<>();
            while (resultSet.next()) {
                long orderId = resultSet.getLong("id");
                long customerId = resultSet.getLong("customer_id");
                orders.add(new OrderSummary(
                        orderId,
                        customerNames.getOrDefault(customerId, "Customer #" + customerId),
                        resultSet.getString("status"),
                        resultSet.getDate("opened_on").toLocalDate(),
                        resultSet.getDate("due_on").toLocalDate(),
                        money(resultSet.getBigDecimal("total_amount")),
                        invoiceCodes.get(orderId),
                        List.copyOf(linesByOrderId.getOrDefault(orderId, List.of()))
                ));
            }
            orders.sort(Comparator.comparing(OrderSummary::openedOn).thenComparing(OrderSummary::id).reversed());
            return List.copyOf(orders);
        }
    }

    private OrderSummary loadOrder(Connection connection, long orderId) throws SQLException {
        return loadOrders(connection).stream()
                .filter(order -> order.id() == orderId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Order " + orderId + " does not exist."));
    }

    private Map<Long, String> loadEmployeeNames(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, full_name FROM employees");
             ResultSet resultSet = statement.executeQuery()) {
            Map<Long, String> names = new LinkedHashMap<>();
            while (resultSet.next()) {
                names.put(resultSet.getLong("id"), resultSet.getString("full_name"));
            }
            return names;
        }
    }

    private Map<Long, String> loadCustomerNames(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, name FROM customers");
             ResultSet resultSet = statement.executeQuery()) {
            Map<Long, String> names = new LinkedHashMap<>();
            while (resultSet.next()) {
                names.put(resultSet.getLong("id"), resultSet.getString("name"));
            }
            return names;
        }
    }

    private Map<Long, String> loadProductNames(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, name FROM products");
             ResultSet resultSet = statement.executeQuery()) {
            Map<Long, String> names = new LinkedHashMap<>();
            while (resultSet.next()) {
                names.put(resultSet.getLong("id"), resultSet.getString("name"));
            }
            return names;
        }
    }

    private Map<Long, String> loadInvoiceCodes(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT order_id, invoice_code FROM invoices");
             ResultSet resultSet = statement.executeQuery()) {
            Map<Long, String> invoiceCodes = new LinkedHashMap<>();
            while (resultSet.next()) {
                invoiceCodes.put(resultSet.getLong("order_id"), resultSet.getString("invoice_code"));
            }
            return invoiceCodes;
        }
    }

    private Map<Long, List<OrderLineView>> loadOrderLines(Connection connection, Map<Long, String> productNames)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, order_id, line_no, product_id, quantity, unit_price, line_total FROM sales_order_items");
             ResultSet resultSet = statement.executeQuery()) {
            Map<Long, List<OrderLineView>> linesByOrderId = new LinkedHashMap<>();
            while (resultSet.next()) {
                long orderId = resultSet.getLong("order_id");
                long productId = resultSet.getLong("product_id");
                linesByOrderId.computeIfAbsent(orderId, ignored -> new ArrayList<>())
                        .add(new OrderLineView(
                                resultSet.getLong("id"),
                                resultSet.getInt("line_no"),
                                productNames.getOrDefault(productId, "Product #" + productId),
                                resultSet.getInt("quantity"),
                                money(resultSet.getBigDecimal("unit_price")),
                                money(resultSet.getBigDecimal("line_total"))
                        ));
            }
            linesByOrderId.values().forEach(lines -> lines.sort(Comparator.comparing(OrderLineView::lineNo)));
            return linesByOrderId;
        }
    }

    private List<ProductSelection> resolveSelections(Connection connection, List<OrderLineRequest> lines)
            throws SQLException {
        List<ProductSelection> selections = new ArrayList<>();
        Set<Long> seenProductIds = new LinkedHashSet<>();
        for (OrderLineRequest line : lines) {
            if (line.productId() == null) {
                throw new IllegalArgumentException("Each order line requires a product.");
            }
            if (line.quantity() == null || line.quantity() <= 0) {
                throw new IllegalArgumentException("Each order line quantity must be positive.");
            }
            if (!seenProductIds.add(line.productId())) {
                throw new IllegalArgumentException("Each product may appear only once per order in this demo.");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, name, unit_price, stock_qty FROM products WHERE id = ?")) {
                statement.setLong(1, line.productId());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalArgumentException("Product " + line.productId() + " does not exist.");
                    }
                    int stockQty = resultSet.getInt("stock_qty");
                    if (stockQty < line.quantity()) {
                        throw new IllegalArgumentException(
                                "Insufficient stock for " + resultSet.getString("name") + ". Requested "
                                        + line.quantity() + ", available " + stockQty + "."
                        );
                    }
                    BigDecimal unitPrice = money(resultSet.getBigDecimal("unit_price"));
                    BigDecimal lineTotal = money(unitPrice.multiply(BigDecimal.valueOf(line.quantity())));
                    selections.add(new ProductSelection(
                            resultSet.getLong("id"),
                            resultSet.getString("name"),
                            line.quantity(),
                            unitPrice,
                            lineTotal,
                            stockQty - line.quantity()
                    ));
                }
            }
        }
        return selections;
    }

    private void ensureEmployeeExists(Connection connection, long employeeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM employees WHERE id = ?")) {
            statement.setLong(1, employeeId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("Employee " + employeeId + " does not exist.");
                }
            }
        }
    }

    private OrderState loadOrderState(Connection connection, long orderId) throws SQLException {
        String invoiceCode = null;
        try (PreparedStatement invoiceStatement = connection.prepareStatement(
                "SELECT invoice_code FROM invoices WHERE order_id = ?")) {
            invoiceStatement.setLong(1, orderId);
            try (ResultSet invoiceResult = invoiceStatement.executeQuery()) {
                if (invoiceResult.next()) {
                    invoiceCode = invoiceResult.getString("invoice_code");
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, status, total_amount FROM sales_orders WHERE id = ?")) {
            statement.setLong(1, orderId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("Order " + orderId + " does not exist.");
                }
                return new OrderState(
                        resultSet.getLong("id"),
                        resultSet.getString("status"),
                        money(resultSet.getBigDecimal("total_amount")),
                        invoiceCode
                );
            }
        }
    }

    private long generatedKey(Statement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new IllegalStateException("Database did not return a generated key.");
            }
            return keys.getLong(1);
        }
    }

    private String stockState(int stockQty, int reorderPoint) {
        if (stockQty <= Math.max(1, reorderPoint / 2)) {
            return "CRITICAL";
        }
        if (stockQty <= reorderPoint) {
            return "LOW";
        }
        return "READY";
    }

    private String invoiceCode(long orderId) {
        String stamp = LocalDate.now(clock).toString().replace("-", "");
        return "INV-" + orderId + "-" + stamp;
    }

    private BigDecimal money(BigDecimal amount) {
        return amount == null ? ZERO_MONEY : amount.setScale(2, RoundingMode.HALF_UP);
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private record ProductSelection(long productId, String productName, int quantity, BigDecimal unitPrice,
                                    BigDecimal lineTotal, int remainingStock) {
    }

    private record OrderState(long orderId, String status, BigDecimal totalAmount, String invoiceCode) {
    }
}
