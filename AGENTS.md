# AGENTS.md

This document orients automated coding agents (and new human contributors)
working on **`casciian-spring`**. It captures the project layout, the
conventions actually used in this codebase, the commands that exist today,
and the parts of the parent [`casciian`][casciian] library you will most
often need to reach into.

Keep it short, accurate, and verified — if something here disagrees with
the code, **the code wins**; please update this file in the same PR.

---

## 1. What this repository is

`casciian-spring` is a Gradle multi-project that ships the Spring Boot
integration for the Casciian TUI library.

| Subproject                     | Purpose                                                                                                                                     | Published?               |
| ------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------ |
| `casciian-spring-boot-starter` | Spring Boot 3.x auto-configuration that exposes a Casciian `TApplication` over **SSH** (Apache MINA SSHD) and/or a **Unix domain socket**.  | Yes — Maven Central.     |
| `demo-shop`                    | Runnable Spring Boot demo: customer-facing Thymeleaf shop + admin Casciian TUI sharing the same H2-backed `ProductRepository`.              | No — demo only.          |

Parent project: [`crramirez/casciian`][casciian] (the core TUI library,
descended from Autumn Lamonte's public-domain Casciian/Jexer codebase).
Sister scaffold: [`casciian-app-template`][template] — useful when an
agent is asked to spin up a brand-new Casciian application from scratch.

---

## 2. Repository layout

```
casciian-spring/
├── build.gradle                         # Root build: toolchain, JUnit Platform, encoding.
├── settings.gradle                      # Includes the two subprojects.
├── gradle.properties                    # Pinned versions (see §3).
├── gradlew / gradlew.bat / gradle/      # Gradle wrapper — always use it.
├── README.md
├── LICENSE                              # Apache-2.0.
│
├── casciian-spring-boot-starter/
│   ├── build.gradle                     # java-library + maven-publish + signing + release.
│   ├── README.md                        # User-facing docs for the starter.
│   └── src/
│       ├── main/java/io/github/crramirez/casciian/spring/
│       │   ├── CasciianSshAutoConfiguration.java
│       │   ├── CasciianSshProperties.java
│       │   ├── CasciianSshServer.java               # SmartLifecycle wrapper around MINA SSHD.
│       │   ├── CasciianShellFactory.java            # Per-channel TApplication wiring.
│       │   ├── SshSessionContext.java               # Record with username/PTY metadata.
│       │   ├── SshSessionInfoInputStream.java       # Bridges SSH PTY resize events into Casciian.
│       │   ├── CasciianUnixSocketAutoConfiguration.java
│       │   ├── CasciianUnixSocketProperties.java
│       │   ├── CasciianUnixSocketServer.java
│       │   ├── UnixSessionInfoInputStream.java
│       │   ├── CasciianTApplicationFactory.java     # ★ The one interface users must implement.
│       │   └── client/
│       │       ├── CasciianConsoleClient.java       # In-container thin client for the Unix socket.
│       │       └── CasciianConsoleProtocol.java     # Wire format shared with the server.
│       ├── main/resources/META-INF/
│       │   ├── spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
│       │   └── additional-spring-configuration-metadata.json
│       └── test/java/...                # JUnit 5 + AssertJ + Mockito unit tests, one per class.
│
└── demo-shop/
    ├── build.gradle                     # org.springframework.boot + dependency-management.
    ├── README.md
    └── src/
        ├── main/java/io/github/crramirez/casciian/demo/shop/
        │   ├── DemoShopApplication.java         # @SpringBootApplication + console-client switch.
        │   ├── Product.java                     # JPA entity.
        │   ├── ProductRepository.java           # Spring Data JPA repo.
        │   ├── ProductFakerSeeder.java          # DataFaker seeder.
        │   ├── ShopController.java              # Customer-facing Thymeleaf controller.
        │   └── admin/
        │       ├── AdminTuiConfig.java          # @Bean CasciianTApplicationFactory.
        │       ├── AdminTApplication.java       # Casciian TApplication subclass.
        │       └── ProductFormWindow.java       # Reusable TWindow for create/edit.
        ├── main/resources/
        │   ├── application.yml                  # ★ Canonical example of starter configuration.
        │   └── templates/catalogue.html         # Thymeleaf customer view.
        └── test/java/...                        # @SpringBootTest + plain JUnit unit tests.
```

`★` marks the files an agent will reach for most often when answering
"how do users wire this up?".

---

## 3. Versions & toolchain (single source of truth: `gradle.properties`)

| Property                       | Current value | Notes                                                                  |
| ------------------------------ | ------------- | ---------------------------------------------------------------------- |
| `version`                      | `1.0.1-SNAPSHOT` | Bumped by the `net.researchgate.release` plugin in the starter.     |
| `casciianVersion`              | `1.4.1`       | The parent TUI library on Maven Central (`io.github.crramirez:casciian`). |
| `springBootVersion`            | `3.4.1`       | Boot dependencies BOM used by both subprojects.                        |
| `sshdVersion`                  | `2.17.1`      | Apache MINA SSHD core.                                                 |
| `datafakerVersion`             | `2.4.2`       | `demo-shop` only — random product seeder.                              |
| `springBootGradlePluginVersion`| `3.4.1`       | Pinned separately because the plugin is applied in `demo-shop`.        |

* **Java toolchain:** 21 (configured in the root `build.gradle` via
  `JavaLanguageVersion.of(21)` for every `java` subproject — do not
  downgrade).
* **Test platform:** JUnit Platform / JUnit 5 with AssertJ and Mockito
  (`testLogging { events 'passed', 'skipped', 'failed' }`).
* **Compiler flags:** `-Xlint:all -Xlint:-processing -Xlint:-serial`,
  UTF-8 encoding. Lint warnings should stay clean.

When upgrading a version, change it in `gradle.properties` only — every
`build.gradle` interpolates from there.

---

## 4. Build, test, run

Always use the wrapper (`./gradlew`, never a system `gradle`). The
wrapper auto-provisions the required Gradle and Java toolchain.

```sh
# Compile + test + assemble both subprojects
./gradlew build

# Tests only
./gradlew test
./gradlew :casciian-spring-boot-starter:test
./gradlew :demo-shop:test

# Run the demo (web on :8080, SSH on :2222, socket at /tmp/casciian.sock)
./gradlew :demo-shop:bootRun

# Build the starter's publishable artifacts locally
./gradlew :casciian-spring-boot-starter:publishToLocalStaging
./gradlew :casciian-spring-boot-starter:centralBundleZip
# Output: casciian-spring-boot-starter/build/central-bundle/casciian-central-<version>.zip
```

Interactive sanity checks for the demo:

```sh
# Customer view
xdg-open http://localhost:8080/

# Admin TUI over SSH (defaults — credentials are demo-only)
ssh admin@localhost -p 2222     # password: admin

# Admin TUI over the Unix socket (same JVM, no SSH daemon needed)
java -jar demo-shop/build/libs/demo-shop-*.jar console
```

There is **no separate lint task** (e.g. no Checkstyle / Spotless / PMD).
Code quality is enforced via `javac -Xlint:all`, tests, and code review.
Do **not** add a new linter unless you are explicitly asked to.

---

## 5. Architecture in one screen

```
                        ┌────────────────────────────────────────────────────┐
                        │  Application JVM (Spring Boot 3.x, Java 21)        │
                        │                                                    │
   browser  ───HTTP────▶│  Spring MVC / Thymeleaf       Spring beans         │
                        │  (e.g. ShopController)        (repositories,       │
                        │                                services, security) │
                        │                                       ▲            │
                        │  CasciianSshAutoConfiguration         │ shared     │
                        │  ├── PasswordAuthenticator (default or user-supplied)
                        │  └── CasciianSshServer ──▶ CasciianShellFactory ──▶│
                        │                              ↳ CasciianTApplicationFactory  ──▶ new TApplication per session
                        │                                       ▲            │
                        │  CasciianUnixSocketAutoConfiguration  │            │
                        │  └── CasciianUnixSocketServer ────────┘            │
   ssh -p 2222 ────SSH─▶│                                                    │
   `java -jar … console`──IPC─▶ CasciianConsoleClient ↔ Unix socket          │
                        └────────────────────────────────────────────────────┘
```

Key invariants — **don't break these**:

1. **One `TApplication` per connection.** Casciian `TApplication` owns
   mutable UI state; sharing across terminals corrupts the screen.
   The factory bean is the singleton; the products it returns are not.
   See `CasciianTApplicationFactory` Javadoc and `AdminTuiConfig`.
2. **Every auto-configured bean uses `@ConditionalOnMissingBean`** so
   users can override any single piece (authenticator, shell factory,
   the whole SSH server, the Unix-socket server). Preserve this when
   adding new beans.
3. **Servers are Spring `SmartLifecycle`s** — they start/stop with the
   application context. Don't replace them with `@PostConstruct` hooks.
4. **No raw ANSI sequences from your code.** When you need to clean the
   terminal up at the end of a session, call `TApplication.restoreConsole()`
   from `onExit()` — see `AdminTApplication.onExit()` for the canonical
   pattern. The default `onExit()` is a no-op hook documented for
   subclass cleanup.
5. **SSH binds to `127.0.0.1` by default** and the default
   `PasswordAuthenticator` rejects everything unless both
   `casciian.ssh.username` and `casciian.ssh.password` are set. Keep
   these safe defaults intact in any property change.
6. **The Unix-socket listener has no authentication of its own** — it
   relies on file permissions (`casciian.unix-socket.permissions`,
   default `600`). Any change here is a security-sensitive change.

---

## 6. Coding conventions

These come from inspecting every file under `src/main/java` and `src/test/java`.

* **Package layout**
  * Starter: `io.github.crramirez.casciian.spring` (auto-config classes
    live directly in this package; the thin client lives in the
    `.client` sub-package).
  * Demo: `io.github.crramirez.casciian.demo.shop` with an `admin`
    sub-package for the TUI side.
* **License header.** Every Java file starts with an Apache-2.0 header
  in the existing form (see any class). New files **must** carry the
  same header.
* **Javadoc.** Public types and public methods have Javadoc. Keep the
  HTML-light style already in use (`<p>`, `<ul>`, `{@link …}`,
  `{@code …}`). The starter's `javadoc` task is configured for the
  Java 21 API.
* **`final` everywhere reasonable.** Parameters, locals, and fields
  that aren't reassigned are declared `final` throughout the existing
  code; match that style.
* **Spring properties.** Use `@ConfigurationProperties` records with
  validated defaults (`CasciianSshProperties`, `CasciianUnixSocketProperties`).
  Document new properties in **two** places: the class Javadoc **and**
  the README's property tables.
* **Configuration metadata.** When you add a property, also describe it
  in `casciian-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json`
  so IDE autocomplete keeps working.
* **Auto-configuration registration.** New `@AutoConfiguration` classes
  must be appended to
  `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
* **Logging.** SLF4J via `LoggerFactory.getLogger(...)`. Use parameterised
  messages (`LOGGER.info("… {} …", value)`) — no string concatenation.
* **Error messages and i18n.** Strings shown to operators in the TUI go
  through `TMessageBox`; keep them short and actionable
  (see `AdminTApplication.createProduct`).
* **Locale-sensitive formatting.** Use `Locale.ROOT` (or an explicit
  locale) for any string the operator sees in the TUI, so column
  alignment doesn't break under non-en locales — `AdminTApplication.formatPrice`
  is the reference.

---

## 7. Testing conventions

The repository follows a deliberately narrow "fast unit tests + one
slice integration test" model.

### What's already in place

* **JUnit 5 + AssertJ + Mockito** (mockito-junit-jupiter for
  `@ExtendWith(MockitoExtension.class)` style if you need it). Spring
  Boot test BOM aligns versions for `demo-shop`.
* **Naming.** `FooTest` for unit tests, `FooIntegrationTest` for
  Spring-context tests (see `ShopControllerIntegrationTest`). One test
  class per production class.
* **Black-box tests of the auto-config wiring.** `CasciianShellFactoryTest`,
  `CasciianSshServerTest`, `CasciianUnixSocketServerTest`,
  `CasciianSshAutoConfigurationTest`, and
  `CasciianUnixSocketAutoConfigurationTest` exercise the contract
  (e.g. "one `create()` per shell invocation", "streams plumbed
  through", "PTY geometry read from environment") without actually
  binding to TCP. Mock SSH primitives (`ChannelSession`, `Environment`,
  `ExitCallback`) rather than starting a real `SshServer`.
* **In-process socket tests.** `CasciianUnixSocketServerTest` uses a
  temp-file path under `@TempDir` and shuts the server down in
  `@AfterEach`. Apply the same pattern for any new socket plumbing.
* **`@SpringBootTest` only when you need the whole context.**
  `ShopControllerIntegrationTest` disables the SSH listener explicitly
  (`"casciian.ssh.enabled=false"`) so the test doesn't try to bind
  `:2222`. **Always** disable transports that aren't under test.
* **Don't drive the real terminal.** Pure logic (`AdminTApplication.formatRow`,
  the price formatter) is package-private specifically so it can be
  tested without standing up a `TApplication` against a TTY — see
  `AdminTApplicationFormatRowTest`. Prefer extracting and testing the
  logic in the same way.

### How to test new code

| Kind of change                                              | Test recipe                                                                                                                                                |
| ----------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Pure helper / formatter / parser                            | Plain JUnit + AssertJ. Keep the method package-private if it has no other callers.                                                                         |
| New `@ConfigurationProperties` field                        | Add a unit test that binds the property via `@SpringBootTest(properties = "...")` or `ApplicationContextRunner`, asserts the bean sees the value, and confirms the default. |
| New `@ConditionalOnMissingBean` extension point              | One test that the default bean appears when the user supplies nothing; one test that a user `@Bean` overrides it. Use Boot's `ApplicationContextRunner`.    |
| Changes to `CasciianShellFactory` / SSH lifecycle            | Extend the existing pattern in `CasciianShellFactoryTest`: pass mocked `ChannelSession` + `Environment`, assert one `create()` per `start()`, verify streams and `SshSessionContext` fields. |
| Changes to `CasciianUnixSocketServer` / IPC framing          | Start the server on a `@TempDir` path, connect with a local `SocketChannel`, exchange `CasciianConsoleProtocol` frames, assert behaviour. See `CasciianUnixSocketServerTest` + `CasciianConsoleClientTest`. |
| TUI behaviour (menu actions, list snapshot, formatting)      | Extract the logic into a package-private static method (`formatRow`-style) and unit-test it. Do **not** boot a real `TApplication` in CI.                  |
| End-to-end demo behaviour                                    | `@SpringBootTest` with `casciian.ssh.enabled=false`, `casciian.unix-socket.enabled=false`, `spring.jpa.hibernate.ddl-auto=create-drop`. See `ShopControllerIntegrationTest`. |

### How tests are run

```sh
./gradlew test                                  # everything
./gradlew :casciian-spring-boot-starter:test    # starter only
./gradlew :demo-shop:test                       # demo only
./gradlew :demo-shop:test --tests '*ShopControllerIntegrationTest'
```

Test reports land at
`<subproject>/build/reports/tests/test/index.html`. Failures print full
stack traces (`exceptionFormat = 'full'`).

---

## 8. Working with the parent Casciian API (cheat-sheet)

The starter depends on `io.github.crramirez:casciian:${casciianVersion}`
as an `api` dependency, so the symbols below are part of the starter's
public surface. When in doubt, browse the parent repo:
<https://github.com/crramirez/casciian>.

* `casciian.TApplication` — Subclass it for your TUI. Build menus and
  windows in the constructor (see `AdminTApplication`). Override
  `onExit()` for per-session cleanup; call `restoreConsole()` from it
  to reset the operator's terminal cleanly.
* `casciian.TWindow` — Subclass for stateful windows (see
  `ProductCatalogueWindow` and `ProductFormWindow`). Override `onResize`
  if your window contains widgets that need to track its geometry.
* `casciian.TList`, `casciian.TMessageBox`, `casciian.TAction` — Common
  widgets and the standard event/callback type. Note that `TAction.DO()`
  is the (legacy) callback name; keep using it.
* `casciian.menu.TMenu` and `casciian.event.TMenuEvent` — Build menus
  via `addMenu("&Title")` and `addItem(id, "&Label")`; route them in
  `onMenu(TMenuEvent)`. Use integer IDs in a private range
  (the demo uses `9001+`) so they don't collide with framework IDs.
* `casciian.event.TResizeEvent` — Distinguish `Type.WIDGET` from
  `Type.SCREEN` when forwarding to children, as `ProductCatalogueWindow.onResize`
  does.
* `casciian.backend.SessionInfo` — How the starter conveys PTY geometry
  and terminal type to Casciian. The starter's
  `SshSessionInfoInputStream` / `UnixSessionInfoInputStream` are the
  only places that should produce `SessionInfo` updates; user code
  consumes them via `TResizeEvent`.

If you are scaffolding a brand-new Casciian application (not a Spring
integration), point the user at [`casciian-app-template`][template]
rather than copy-pasting `AdminTApplication` out of `demo-shop`.

---

## 9. Release / publishing

The starter is published to Maven Central via the Sonatype Central
Portal "bundle upload" flow.

* `net.researchgate.release` drives the version bump (`./gradlew release`).
  Tag template: `v${version}`.
* `./gradlew :casciian-spring-boot-starter:publishToLocalStaging`
  signs and writes a Maven-layout staging repo under
  `casciian-spring-boot-starter/build/staging-repo/`.
* `./gradlew :casciian-spring-boot-starter:centralBundleZip` zips the
  staging repo to
  `casciian-spring-boot-starter/build/central-bundle/casciian-central-<version>.zip`
  for upload to the Central Portal.
* Signing keys come from Gradle properties / env vars:
  `signingKeyId`, `signingKeyB64` (base64-encoded ASCII-armored private
  key), `signingPassword`. Signing is silently skipped if any of the
  three is missing — useful for local builds, fatal in releases.
* **Never** commit signing material, `gradle.properties` overrides
  containing credentials, or `~/.gradle/gradle.properties` snippets.

The `demo-shop` subproject's plain `jar` task is disabled on purpose;
its only runnable artifact is the Spring Boot fat jar produced by
`bootJar`. Don't re-enable `jar` without a strong reason.

---

## 10. Things an agent should typically *not* do

* Don't downgrade the Java toolchain (21) or Spring Boot major version
  (3.x) — both are baked into the public contract.
* Don't add Lombok, AspectJ, or other annotation processors. The
  starter intentionally has only `spring-boot-configuration-processor`.
* Don't introduce reactive (`spring-boot-starter-webflux`) or Spring
  Shell — the whole point of this starter is that it is *not* Spring
  Shell. The starter sits next to your existing web stack, whatever it
  is.
* Don't bind the SSH listener to `0.0.0.0` by default, and don't drop
  authentication on either transport without explicit user opt-in.
* Don't add tests that bind to fixed ports (`2222`, `8080`, …) — use
  ephemeral ports or disable the transport for the test as
  `ShopControllerIntegrationTest` does.
* Don't write to the real terminal from tests, and don't assume a TTY
  in CI.
* Don't edit files under `.github/agents/` (instructions for other
  agents) or commit anything under `build/`, `.gradle/`, `out/`, or
  `.idea/` — see `.gitignore`.

---

## 11. Quick links

* Parent TUI library — <https://github.com/crramirez/casciian>
* New-app scaffold — <https://github.com/crramirez/casciian-app-template>
* MINA SSHD docs — <https://mina.apache.org/sshd-project/>
* Spring Boot 3.4 reference — <https://docs.spring.io/spring-boot/docs/3.4.x/reference/html/>
* Starter README (user-facing) — [`casciian-spring-boot-starter/README.md`](./casciian-spring-boot-starter/README.md)
* Demo README — [`demo-shop/README.md`](./demo-shop/README.md)

[casciian]: https://github.com/crramirez/casciian
[template]: https://github.com/crramirez/casciian-app-template
