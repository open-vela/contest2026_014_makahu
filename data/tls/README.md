# Buffer HTTPS trust anchors

Public Mozilla CA roots copied from the local Ubuntu ca-certificates package under `/usr/share/ca-certificates/mozilla` on 2026-10-16. No `/usr/local` or locally installed trust roots are included. This is TLS service trust, separate from Fabric peer identity/trust.

The app embeds `buffer_agent_roots.h` so firmware has CA roots without a runtime downloader. A build-time `CONFIG_BUFFER_VELA_AGENT_CA_FILE` may replace them for a managed deployment or an isolated test CA; application messages cannot change that path or disable verification. Missing/invalid CA files fail closed. Update the PEM asset and generated header together.

Bundle SHA-256: `9481fcd95f41b221f02f14d896535fe500bec539bc563c4cdca1acee483a8bdd`
Certificate count: 121

Certificates retain their issuers' terms; the source package uses the Mozilla CA certificate distribution.
