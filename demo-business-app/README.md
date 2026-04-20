# DaisyBase Demo Business Application

This module packages a TomEE-compatible WAR that runs a small enterprise operations cockpit on top of DaisyBase and the DaisyBase JDBC driver.

## What It Demonstrates

- TomEE-hosted Jakarta EE 10 backend using JAX-RS and CDI
- DaisyBase JDBC integration in embedded mode
- enterprise CRUD and operations flows using plain JDBC
- generated keys for business inserts
- callable JDBC usage for order fulfillment routines
- a static frontend served from the WAR

## Demo Surface

Backend endpoints:

- `GET /api/dashboard`
- `GET /api/directory/employees`
- `GET /api/directory/customers`
- `POST /api/directory/customers`
- `GET /api/directory/products`
- `POST /api/directory/products`
- `GET /api/orders`
- `POST /api/orders`
- `POST /api/orders/{orderId}/fulfill`

Frontend workflows:

- operations dashboard and risk radar
- employee, customer, and product directories
- customer and product creation through generated keys
- order creation with live stock deduction
- order fulfillment through a stored procedure and stored function

## Build

```powershell
./gradlew.bat :demo-business-app:war
```

The WAR is written to:

- `demo-business-app/build/libs/daisybase-demo-business-0.1.0-SNAPSHOT.war`

## TomEE Deployment

1. Build the WAR.
2. Copy the WAR into a TomEE 10 `webapps/` directory.
3. Start TomEE.
4. Open `http://localhost:8080/daisybase-demo-business-0.1.0-SNAPSHOT/`.

Example against the local TomEE install on this machine:

```powershell
Copy-Item .\demo-business-app\build\libs\daisybase-demo-business-0.1.0-SNAPSHOT.war `
  C:\Users\tabur\Downloads\apache-tomee-10.1.2-plus\webapps\
```

## Runtime Configuration

The application reads these system properties or environment variables at startup:

- `daisybase.demo.jdbcUrl` or `JAVADB_DEMO_JDBC_URL`
- `daisybase.demo.user` or `JAVADB_DEMO_USER`
- `daisybase.demo.password` or `JAVADB_DEMO_PASSWORD`
- `daisybase.demo.enterpriseName` or `JAVADB_DEMO_ENTERPRISE_NAME`

Default JDBC URL:

- `jdbc:daisybase:embedded:${user.home}/.daisybase/demo-business`

The application bootstraps its own schema and seed data on first startup.

## Local TomEE Target

A compatible local install already exists on this machine:

- `C:\Users\tabur\Downloads\apache-tomee-10.1.2-plus`
