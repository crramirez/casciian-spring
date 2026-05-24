# casciian-spring

This Gradle multi-project hosts the Casciian Spring Boot integration:

| Subproject | Purpose |
| ---------- | ------- |
| [`casciian-spring-boot-starter`](./casciian-spring-boot-starter) | Spring Boot 3.x auto-configuration that exposes a Casciian TUI over SSH and/or a Unix domain socket. Published to Maven Central. |
| [`demo-shop`](./demo-shop) | Runnable Spring Boot demo: a customer-facing web shop **and** an admin TUI that operate on the same H2-backed product catalogue. |

```sh
# Build everything (starter + demo)
./gradlew build

# Run the demo
./gradlew :demo-shop:bootRun
```

Once the demo is running:

* Customers see the product catalogue at <http://localhost:8080/>.
* Operators run CRUD over the same database from a terminal in one of two ways:
  * Over SSH: `ssh admin@localhost -p 2222` (password `admin`).
  * Over a Unix domain socket (e.g. from inside the container via
    `docker exec` / `kubectl exec`): re-invoke the same JAR with the
    `console` argument — `java -jar build/libs/demo-shop-*.jar console` —
    and it acts as a thin terminal client that attaches to the running
    JVM via `/tmp/casciian.sock`.

See the per-subproject READMEs for details — most consumers of the starter only
need to read [`casciian-spring-boot-starter/README.md`](./casciian-spring-boot-starter/README.md).

