# DaisyBase Demo Business Application

This module packages a TomEE-compatible WAR that runs a small operations cockpit on top of DaisyBase and the DaisyBase JDBC driver.

The point of the demo is not spectacle. It is to show what the database looks like inside an ordinary business application: REST endpoints, frontend pages, generated keys, stored routines, and a useful slice of operational workflow.

## What the Demo Shows

- Jakarta EE 10 backend using JAX-RS and CDI
- embedded DaisyBase usage through JDBC
- customer, product, and order workflows
- generated-key inserts for business entities
- callable JDBC for fulfillment routines
- a deployable frontend served directly from the WAR

## API Surface

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

## Frontend Surface

The frontend provides:

- an operations dashboard
- employee, customer, and product directories
- customer and product creation
- order creation with stock-aware behavior
- order fulfillment through stored procedure and function calls

## Build

```powershell
./gradlew.bat :demo-business-app:war
```

Output:

- `demo-business-app/build/libs/daisybase-demo-business-0.1.0-SNAPSHOT.war`

## Deploy to TomEE

1. Build the WAR.
2. Copy it into a TomEE 10 `webapps/` directory.
3. Start TomEE.
4. Open `http://localhost:8080/daisybase-demo-business-0.1.0-SNAPSHOT/`.

Example:

```powershell
Copy-Item .\demo-business-app\build\libs\daisybase-demo-business-0.1.0-SNAPSHOT.war `
  C:\Users\tabur\Downloads\apache-tomee-10.1.2-plus\webapps\
```

## Configuration

The application reads these system properties or environment variables:

- `daisybase.demo.jdbcUrl` or `JAVADB_DEMO_JDBC_URL`
- `daisybase.demo.user` or `JAVADB_DEMO_USER`
- `daisybase.demo.password` or `JAVADB_DEMO_PASSWORD`
- `daisybase.demo.enterpriseName` or `JAVADB_DEMO_ENTERPRISE_NAME`

Default JDBC URL:

- `jdbc:daisybase:embedded:${user.home}/.daisybase/demo-business`

On first startup, the application bootstraps its own schema and seed data.

## Local TomEE Note

On this machine, a compatible TomEE install already exists at:

- `C:\Users\tabur\Downloads\apache-tomee-10.1.2-plus`
