# casciian-spring-boot-starter

Spring Boot 3.x auto-configuration that embeds a [Casciian][casciian] TUI
into your Spring application and exposes it over SSH **or** over a Unix
domain socket. Drop the jar on the classpath, point an SSH client (or the
bundled console client) at the configured endpoint, and you get a
text-mode admin/management view running in the same JVM as your web app —
with full access to your Spring beans.

## Why

Spring gives you a web view for your users. Sometimes you also want a
terminal view for operators: a richer alternative to a Spring Shell prompt.

Two transports are supported, and you can enable either, both, or neither:

* **SSH** (default port 2222) — for remote access from operator laptops,
  bastion hosts, etc. Backed by [Apache MINA SSHD][sshd].
* **Unix domain socket** (default path `/tmp/casciian.sock`) — for
  containerised deployments. Operators already reach the container with
  `docker exec`, `kubectl exec`, or an ArgoCD terminal; running the same
  application JAR a second time with a single argument turns it into a
  tiny in-container client that attaches to the TUI over IPC. No SSH
  daemon needs to be installed in the image.

Each connection gets its own `TApplication` instance so per-operator UI
state is isolated.

By default the SSH listener binds to `127.0.0.1`, so it's only reachable
from the same host. To expose it on a network interface, set
`casciian.ssh.host` explicitly (e.g. `0.0.0.0` for all interfaces, or a
specific IP) — and put it behind appropriate authentication and firewall
rules before doing so.

For a complete, runnable example see the sibling [`demo-shop`](../demo-shop)
subproject.

## Getting started

### 1. Add the dependency

```groovy
dependencies {
    implementation 'io.github.crramirez:casciian-spring-boot-starter:0.1.0-SNAPSHOT'
}
```

Requires Java 21 and Spring Boot 3.x.

### 2. Provide a `CasciianTApplicationFactory` bean

The factory is a **singleton** that produces a **fresh `TApplication` per
session**. It can freely inject Spring beans — your repositories,
services, security context — and hand them to each new TUI instance.

```java
@Configuration
public class AdminTuiConfig {

    @Bean
    CasciianTApplicationFactory adminTuiFactory(MyRepository repo,
                                                AuditService audit) {
        return (in, out, session) -> {
            audit.logOpen(session.username(), session.remoteAddress());
            return new AdminApplication(in, out, repo);
        };
    }
}
```

The same factory bean is used by both the SSH listener and the Unix-socket
listener.

### 3. Configure transports

```yaml
casciian:
  ssh:
    enabled: true        # default
    host: 127.0.0.1      # default
    port: 2222           # default
    username: admin
    password: change-me
    host-key-path: ~/.casciian/ssh_host_key  # default; auto-generated if missing
    banner: "Welcome to Acme Admin TUI"
  unix-socket:
    enabled: true        # default false — opt in explicitly
    path: /tmp/casciian.sock   # default
    permissions: "600"          # default; POSIX octal applied to the socket file
```

If `username` or `password` is blank, the built-in SSH authenticator
rejects every attempt — declare your own `PasswordAuthenticator` bean
(e.g. backed by Spring Security) to override. The Unix-socket listener
intentionally has no authentication of its own: it relies on the
filesystem permissions of the socket file, which by default restrict
access to the user running the JVM. That matches the threat model of
`docker exec` / `kubectl exec`, where the container runtime has already
authenticated the operator.

### 4. Connect

Over SSH:

```sh
ssh admin@localhost -p 2222
```

Over the Unix socket (intended for use inside the container itself, after
`kubectl exec`-ing into the pod):

```sh
java -jar /app/app.jar console
```

The `console` argument tells the application's `main` method to bypass
Spring entirely and act as a thin terminal client that connects to the
already-running JVM via the configured socket path. The class behind it
is `io.github.crramirez.casciian.spring.CasciianConsoleClient` — wire it
into your own `main` like this:

```java
public static void main(String[] args) {
    if (CasciianConsoleClient.isConsoleInvocation(args)) {
        System.exit(new CasciianConsoleClient().run());
    }
    SpringApplication.run(MyApp.class, args);
}
```

The client puts the local terminal in raw mode, forwards keystrokes to
the server as framed messages, copies the server's bytes back to stdout,
and polls `stty size` once per second so that resizing the terminal
window is reflected in the TUI.

## Configuration properties

### SSH listener

| Property                      | Default                      | Description                                       |
| ----------------------------- | ---------------------------- | ------------------------------------------------- |
| `casciian.ssh.enabled`        | `true`                       | Turn the SSH listener off.                        |
| `casciian.ssh.host`           | `127.0.0.1`                  | Interface to bind.                                |
| `casciian.ssh.port`           | `2222`                       | TCP port.                                         |
| `casciian.ssh.username`       | —                            | Username for the default password auth.           |
| `casciian.ssh.password`       | —                            | Password for the default password auth.           |
| `casciian.ssh.host-key-path`  | `~/.casciian/ssh_host_key`   | Persistent host key (auto-generated on first run).|
| `casciian.ssh.banner`         | —                            | Optional SSH user-auth banner.                    |

### Unix-socket listener

| Property                          | Default               | Description                                                       |
| --------------------------------- | --------------------- | ----------------------------------------------------------------- |
| `casciian.unix-socket.enabled`    | `false`               | Bind the Unix-domain-socket listener.                             |
| `casciian.unix-socket.path`       | `/tmp/casciian.sock`  | Filesystem path for the socket file. Leading `~` is expanded.     |
| `casciian.unix-socket.permissions`| `600`                 | POSIX permissions applied to the socket file (3- or 4-digit octal).|

## Extension points

Every auto-configured bean is declared with
`@ConditionalOnMissingBean`, so you can override any piece:

| Your bean type                     | Replaces                                       |
| ---------------------------------- | ---------------------------------------------- |
| `PasswordAuthenticator`            | Default username/password from properties.     |
| `ShellFactory`                     | The per-connection Casciian SSH shell factory. |
| `CasciianSshServer`                | The whole SSH `SmartLifecycle`-managed server. |
| `CasciianUnixSocketServer`         | The Unix-socket `SmartLifecycle`-managed server.|

## License

Apache License 2.0.

[casciian]: https://github.com/crramirez/casciian
[sshd]: https://mina.apache.org/sshd-project/
