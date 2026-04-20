"use strict";

const apiBase = new URL("api/", window.location.href);

const state = {
    dashboard: null,
    employees: [],
    customers: [],
    products: [],
    orders: []
};

document.addEventListener("DOMContentLoaded", async () => {
    document.getElementById("date-stamp").textContent = new Date().toLocaleDateString(undefined, {
        year: "numeric",
        month: "long",
        day: "numeric"
    });
    wireForms();
    resetOrderLines();
    await refreshAll();
});

function wireForms() {
    document.getElementById("customer-form").addEventListener("submit", async event => {
        event.preventDefault();
        const form = event.currentTarget;
        const payload = {
            name: form.name.value.trim(),
            segment: form.segment.value.trim(),
            health: form.health.value.trim(),
            accountOwnerId: Number(form.accountOwnerId.value)
        };
        await sendJson("directory/customers", payload, "POST");
        form.reset();
        flash("Customer created.", "success");
        await refreshAll();
    });

    document.getElementById("product-form").addEventListener("submit", async event => {
        event.preventDefault();
        const form = event.currentTarget;
        const payload = {
            sku: form.sku.value.trim(),
            name: form.name.value.trim(),
            unitPrice: Number(form.unitPrice.value),
            stockQty: Number(form.stockQty.value),
            reorderPoint: Number(form.reorderPoint.value)
        };
        await sendJson("directory/products", payload, "POST");
        form.reset();
        flash("Product created.", "success");
        await refreshAll();
    });

    document.getElementById("order-form").addEventListener("submit", async event => {
        event.preventDefault();
        const form = event.currentTarget;
        const lines = Array.from(document.querySelectorAll(".order-line")).map(line => ({
            productId: Number(line.querySelector("select").value),
            quantity: Number(line.querySelector("input").value)
        })).filter(line => Number.isFinite(line.productId) && Number.isFinite(line.quantity) && line.quantity > 0);

        const payload = {
            customerId: Number(form.customerId.value),
            notes: form.notes.value.trim(),
            lines
        };
        await sendJson("orders", payload, "POST");
        form.reset();
        resetOrderLines();
        flash("Order created.", "success");
        await refreshAll();
    });

    document.getElementById("add-line").addEventListener("click", () => addOrderLine());
}

async function refreshAll(message) {
    try {
        const [dashboard, employees, customers, products, orders] = await Promise.all([
            getJson("dashboard"),
            getJson("directory/employees"),
            getJson("directory/customers"),
            getJson("directory/products"),
            getJson("orders")
        ]);
        state.dashboard = dashboard;
        state.employees = employees;
        state.customers = customers;
        state.products = products;
        state.orders = orders;
        render();
        if (message) {
            flash(message, "success");
        }
    } catch (error) {
        flash(error.message, "error");
    }
}

async function fulfillOrder(orderId) {
    try {
        await sendJson(`orders/${orderId}/fulfill`, {}, "POST");
        await refreshAll(`Order ${orderId} fulfilled.`);
    } catch (error) {
        flash(error.message, "error");
    }
}

function render() {
    if (!state.dashboard) {
        return;
    }
    document.getElementById("hero-title").textContent = `${state.dashboard.enterpriseName} operations cockpit`;
    renderMetrics();
    renderAlerts();
    renderEmployees();
    renderCustomers();
    renderProducts();
    renderOrders();
    renderSelects();
}

function renderMetrics() {
    const metrics = state.dashboard.metrics;
    document.getElementById("metrics").innerHTML = [
        metricCard("Active customers", metrics.activeCustomers, "Accounts under live coverage"),
        metricCard("Open orders", metrics.openOrders, "Revenue still in flight"),
        metricCard("Low stock products", metrics.lowStockProducts, "Offers that need replenishment"),
        metricCard("Pipeline value", money(metrics.pipelineValue), "Open order value")
    ].join("");
}

function renderAlerts() {
    const container = document.getElementById("alerts");
    container.innerHTML = state.dashboard.alerts.map(alert => `
        <section class="alert-card tone-${escapeHtml(alert.tone)}">
            <h3>${escapeHtml(alert.title)}</h3>
            <p>${escapeHtml(alert.body)}</p>
        </section>
    `).join("");
}

function renderEmployees() {
    const container = document.getElementById("employees");
    container.innerHTML = state.employees.map(employee => `
        <article class="mini-card">
            <div class="mini-card-row">
                <h3>${escapeHtml(employee.fullName)}</h3>
                <span class="tag">${escapeHtml(employee.status)}</span>
            </div>
            <p>${escapeHtml(employee.title)}</p>
            <p>Capacity: ${escapeHtml(String(employee.capacity))}%</p>
            <p>Daily billable: ${money(employee.dailyBillable)}</p>
        </article>
    `).join("");
}

function renderCustomers() {
    const container = document.getElementById("customers");
    container.innerHTML = state.customers.map(customer => `
        <article class="mini-card">
            <div class="mini-card-row">
                <h3>${escapeHtml(customer.name)}</h3>
                <span class="tag ${customer.health === "AT_RISK" ? "tag-risk" : ""}">${escapeHtml(customer.health)}</span>
            </div>
            <p>${escapeHtml(customer.segment)}</p>
            <p>Owner: ${escapeHtml(customer.accountOwner)}</p>
        </article>
    `).join("");
}

function renderProducts() {
    const container = document.getElementById("products");
    container.innerHTML = state.products.map(product => `
        <article class="mini-card">
            <div class="mini-card-row">
                <h3>${escapeHtml(product.name)}</h3>
                <span class="tag ${product.stockState !== "READY" ? "tag-warn" : ""}">${escapeHtml(product.stockState)}</span>
            </div>
            <p>${escapeHtml(product.sku)}</p>
            <p>${money(product.unitPrice)} per unit</p>
            <p>${escapeHtml(String(product.stockQty))} in stock, reorder at ${escapeHtml(String(product.reorderPoint))}</p>
        </article>
    `).join("");
}

function renderOrders() {
    const container = document.getElementById("orders");
    container.innerHTML = state.orders.map(order => `
        <article class="order-card">
            <div class="mini-card-row">
                <h3>Order #${escapeHtml(String(order.id))}</h3>
                <span class="tag ${order.status === "OPEN" ? "tag-warn" : ""}">${escapeHtml(order.status)}</span>
            </div>
            <p class="order-customer">${escapeHtml(order.customerName)}</p>
            <p>Opened ${escapeHtml(order.openedOn)} · Due ${escapeHtml(order.dueOn)}</p>
            <p>Total ${money(order.totalAmount)}</p>
            <p>${order.invoiceCode ? `Invoice ${escapeHtml(order.invoiceCode)}` : "Invoice pending"}</p>
            <ul class="line-list">
                ${order.lines.map(line => `
                    <li>${escapeHtml(String(line.quantity))} × ${escapeHtml(line.productName)} · ${money(line.lineTotal)}</li>
                `).join("")}
            </ul>
            ${order.status === "OPEN"
                ? `<button class="secondary-button" type="button" onclick="fulfillOrder(${Number(order.id)})">Fulfill</button>`
                : ""}
        </article>
    `).join("");
}

function renderSelects() {
    const customerOptions = state.customers.map(customer =>
        `<option value="${Number(customer.id)}">${escapeHtml(customer.name)}</option>`
    ).join("");
    document.getElementById("customer-owner-select").innerHTML = state.employees.map(employee =>
        `<option value="${Number(employee.id)}">${escapeHtml(employee.fullName)} · ${escapeHtml(employee.title)}</option>`
    ).join("");
    document.getElementById("order-customer-select").innerHTML = customerOptions;
    document.querySelectorAll(".order-line select").forEach(select => {
        const previousValue = select.value;
        select.innerHTML = productOptions();
        if (previousValue) {
            select.value = previousValue;
        }
    });
}

function resetOrderLines() {
    const container = document.getElementById("order-lines");
    container.innerHTML = "";
    addOrderLine();
}

function addOrderLine() {
    const row = document.createElement("div");
    row.className = "order-line";
    row.innerHTML = `
        <label>
            Product
            <select required>${productOptions()}</select>
        </label>
        <label>
            Quantity
            <input type="number" min="1" step="1" value="1" required>
        </label>
        <button class="secondary-button" type="button">Remove</button>
    `;
    row.querySelector("button").addEventListener("click", () => {
        if (document.querySelectorAll(".order-line").length > 1) {
            row.remove();
        }
    });
    document.getElementById("order-lines").appendChild(row);
}

function productOptions() {
    return state.products.map(product =>
        `<option value="${Number(product.id)}">${escapeHtml(product.name)} · ${money(product.unitPrice)} · stock ${escapeHtml(String(product.stockQty))}</option>`
    ).join("");
}

async function getJson(path) {
    const response = await fetch(new URL(path, apiBase), {
        headers: {Accept: "application/json"}
    });
    if (!response.ok) {
        throw new Error(await response.text() || `Request failed: ${response.status}`);
    }
    return response.json();
}

async function sendJson(path, payload, method) {
    const response = await fetch(new URL(path, apiBase), {
        method,
        headers: {
            "Content-Type": "application/json",
            Accept: "application/json"
        },
        body: JSON.stringify(payload)
    });
    if (!response.ok) {
        throw new Error(await response.text() || `Request failed: ${response.status}`);
    }
    const contentType = response.headers.get("content-type") || "";
    if (contentType.includes("application/json")) {
        return response.json();
    }
    return null;
}

function flash(message, tone) {
    const banner = document.getElementById("flash");
    banner.textContent = message;
    banner.className = `flash tone-${tone}`;
}

function metricCard(label, value, body) {
    return `
        <article class="metric-card">
            <p class="metric-label">${escapeHtml(label)}</p>
            <h2>${escapeHtml(String(value))}</h2>
            <p>${escapeHtml(body)}</p>
        </article>
    `;
}

function money(value) {
    const amount = typeof value === "number" ? value : Number(value);
    return new Intl.NumberFormat(undefined, {
        style: "currency",
        currency: "USD"
    }).format(amount);
}

function escapeHtml(value) {
    return String(value).replace(/[&<>"']/g, character => ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        "\"": "&quot;",
        "'": "&#39;"
    })[character]);
}
