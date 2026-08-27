# Deployment examples

Docker Compose is the supported self-hosting path. Copy the root `.env.example` to `.env`, set both database passwords, then run `docker compose up -d --build`.

Compose binds the Web port to `127.0.0.1` by default. Keep that boundary behind a same-host reverse proxy; expose another bind address only when the network access policy is explicit.

The files in `examples/` are production references, not drop-in configuration:

- Replace `example.com` and the TLS certificate paths.
- Review the service user, filesystem paths, proxy trust boundary, rate limits, and retention policy for the target host.
- Keep real certificates, private keys, server inventories, environment files, backups, and operator-specific release scripts outside this repository.

The root `baby-record.service` and `mariadb-baby-record.cnf` are generic hardening examples. The repository intentionally ignores the maintainer's live Nginx and release files.
