# Storage and Recovery

## Storage Model

DaisyBase uses a paged table store with:

- fixed-size pages
- row version encoding
- overflow pages for large payloads
- page checksums
- page image capture for recovery

## Live Heap Path

The current storage layer includes a heap storage manager that:

- tracks dirty pages
- flushes page images
- persists page LSN metadata
- supports recovery replay from WAL page images

## WAL and Recovery

The WAL layer records committed changes and recovery metadata. Restart recovery replays page images while comparing incoming LSNs to persisted page state so stale redo is skipped.

## Current Practical Notes

- the storage system is materially stronger than a snapshot-only persistence model
- executor integration is still evolving toward deeper page-native access on all hot paths
- the architecture is documented with honest limits instead of implying full ARIES parity where the current code does not yet provide it
