# Pinned A2UI v0.9.1 schema inputs

These files are copied verbatim from the official [`a2ui-project/a2ui`]
repository at commit `45633b97636daf89bafb43fa7db23e3cd916461d`.
The commit is immutable; the source tree is
[`specification/v0_9_1`](https://github.com/a2ui-project/a2ui/tree/45633b97636daf89bafb43fa7db23e3cd916461d/specification/v0_9_1).
The repository license at that same commit is Apache-2.0 and is included as
`LICENSE`. `SHA256SUMS` records the SHA-256 of every vendored schema and the
official example fixture used by the tests.

| Local file | Official source path |
| --- | --- |
| `server_to_client.json` | `specification/v0_9_1/json/server_to_client.json` |
| `common_types.json` | `specification/v0_9_1/json/common_types.json` |
| `catalog.json` | `specification/v0_9_1/catalogs/basic/catalog.json` |
| `client_to_server.json` | `specification/v0_9_1/json/client_to_server.json` |
| `LICENSE` | `LICENSE` |
| Test fixture | `specification/v0_9_1/catalogs/basic/examples/00_interactive-button.json` |

The official basic catalog uses references to `catalog.json` as a swappable
catalog placeholder and references the v0.9 common-types URI from within its
v0.9.1 directory. The host schema test resolves those official URIs to these
local pinned files; it does not rewrite the vendored schemas.

The official interactive-button fixture is preserved byte-for-byte. The
upstream example currently labels its messages `v0.9` and uses the v0.9 catalog
URI. The tests first validate that upstream example unchanged against the
v0.9.1 schemas, then change only those protocol/catalog identifiers to the
locked v0.9.1 values before feeding its Column/Text/Button tree to the
production C model. This distinction is intentional: firmware accepts only
`v0.9.1` messages and the exact v0.9.1 basic catalog ID.

[`a2ui-project/a2ui`]: https://github.com/a2ui-project/a2ui
