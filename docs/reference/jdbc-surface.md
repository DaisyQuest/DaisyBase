# JDBC Surface

## URL Forms

- embedded: `jdbc:daisybase:embedded:<path>`
- remote: `jdbc:daisybase:remote://host:port/database`

## Supported Areas

- `Connection`, `Statement`, `PreparedStatement`, `CallableStatement`
- generated keys
- parameter metadata
- database metadata
- savepoints
- multi-result traversal
- updatable result sets for bounded eligible queries
- native JDBC object families on the engine path
- XA data source, connection, and resource with documented recovery scope

## Conformance Position

The driver is documented against the currently supported surface, not against features that are only partially implemented.

## Honest Limits

- updatable result sets are intentionally bounded to eligible direct single-table query shapes
- distributed XA recovery is durable but still bounded in interoperability depth
- security is catalog-backed but not yet a complete enterprise authorization product

See also:

- `docs/jdbc-driver-spec.md`
- `docs/jdbc-testing-matrix.md`
- `docs/jdbc-conformance-review.md`
