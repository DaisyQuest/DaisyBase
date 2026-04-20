# Security and Distributed XA

## Authentication and Authorization

DaisyBase now documents a catalog-backed security model with:

- users
- roles
- grants
- password hashes
- principal-aware authorization checks

Remote sessions authenticate through the server protocol when configured. Embedded sessions select a principal directly through the integration surface.

## XA

DaisyBase documents bounded XA support with:

- `XADataSource`
- `XAConnection`
- `XAResource`
- durable prepare, recover, commit, and rollback state

## Honest Limits

- XA is not yet documented as a full cross-vendor transaction manager product with exhaustive interoperability hardening
- authorization depth is still narrower than a mature enterprise RDBMS security stack
