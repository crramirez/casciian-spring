/*
 * Copyright 2026 Carlos Rafael Ramirez
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.crramirez.casciian.spring;

import io.github.crramirez.casciian.spring.client.CasciianConsoleProtocol;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import casciian.TApplication;

/**
 * Spring {@link SmartLifecycle} bean that owns a Unix-domain-socket
 * listener serving a Casciian {@link TApplication} per connection.
 *
 * <p>This is the lightweight alternative to {@link CasciianSshServer} for
 * containerized deployments where {@code docker exec}, {@code kubectl exec}
 * or ArgoCD already provide an authenticated shell. The Spring-side
 * application binds a socket file (typically under {@code /tmp}), and the
 * tiny {@link io.github.crramirez.casciian.spring.client.CasciianConsoleClient CasciianConsoleClient} bundled in the same JAR connects to
 * that socket to render the TUI on the user's terminal.</p>
 *
 * <p>Frames received from the client are demultiplexed by
 * {@link UnixSessionInfoInputStream}; output bytes from Casciian are
 * forwarded verbatim to the socket.</p>
 */
public class CasciianUnixSocketServer implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(CasciianUnixSocketServer.class);

    private final CasciianUnixSocketProperties properties;
    private final CasciianTApplicationFactory applicationFactory;

    private final AtomicLong sessionCounter = new AtomicLong();
    private final AtomicReference<ServerSocketChannel> serverChannel = new AtomicReference<>();
    private final AtomicReference<Path> socketPath = new AtomicReference<>();

    private volatile boolean running;

    public CasciianUnixSocketServer(final CasciianUnixSocketProperties properties,
                                    final CasciianTApplicationFactory applicationFactory) {
        if (properties == null) {
            throw new IllegalArgumentException("properties must not be null");
        }
        if (applicationFactory == null) {
            throw new IllegalArgumentException("applicationFactory must not be null");
        }
        this.properties = properties;
        this.applicationFactory = applicationFactory;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        final Path path = resolveSocketPath();
        try {
            // The socket file lingers after a non-graceful shutdown; remove
            // a stale entry so bind() does not fail with "address in use".
            Files.deleteIfExists(path);
            final Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            final ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            channel.bind(UnixDomainSocketAddress.of(path));
            applyPermissions(path);
            serverChannel.set(channel);
            socketPath.set(path);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to bind Casciian Unix-socket listener at " + path, e);
        }
        final Thread t = Thread.ofPlatform()
                .name("casciian-unix-accept")
                .daemon(true)
                .unstarted(this::acceptLoop);
        running = true;
        t.start();
        LOG.info("Casciian Unix-socket listener bound at {}", path);
    }

    private void acceptLoop() {
        final ServerSocketChannel channel = serverChannel.get();
        while (running && channel != null && channel.isOpen()) {
            final SocketChannel client;
            try {
                client = channel.accept();
            } catch (ClosedChannelException closed) {
                return; // shutdown
            } catch (IOException e) {
                if (running) {
                    LOG.warn("Casciian Unix-socket accept failed", e);
                }
                return;
            }
            final long id = sessionCounter.incrementAndGet();
            Thread.ofVirtual()
                    .name("casciian-unix-session-" + id)
                    .start(() -> handleClient(client, id));
        }
    }

    /**
     * Per-connection handler. Reads the initial handshake, hands the
     * de-multiplexed input and the socket output to the application
     * factory, then runs the {@link TApplication} until either side closes
     * the channel.
     */
    void handleClient(final SocketChannel client, final long sessionId) {
        try (SocketChannel c = client) {
            try (DataInputStream din = new DataInputStream(Channels.newInputStream(c));
                 OutputStream rawOut = new BufferedOutputStream(Channels.newOutputStream(c))) {
                // First frame must be INIT.
                final int type = din.readByte();
                final int length = din.readInt();
                if (type != CasciianConsoleProtocol.TYPE_INIT) {
                    throw new IOException("Expected INIT frame, got type=" + type);
                }
                if (length < 0 || length > CasciianConsoleProtocol.MAX_PAYLOAD_LENGTH) {
                    throw new IOException("INIT frame length " + length + " out of range");
                }
                final byte[] payload = new byte[length];
                din.readFully(payload);
                final InitFrame init = InitFrame.decode(payload);
                final Path path = socketPath.get();
                final String remote = "unix:" + (path == null ? "?" : path);
                final SshSessionContext context = new SshSessionContext(
                        init.username(), remote, init.terminalType(), init.columns(), init.rows());
                try (UnixSessionInfoInputStream demuxedInput = new UnixSessionInfoInputStream(
                        din, init.username(), init.columns(), init.rows(),
                        "casciian-unix-demux-" + sessionId)) {
                    runApplication(demuxedInput, rawOut, context);
                    final IOException demuxFailure = demuxedInput.getDemuxFailureForTesting();
                    if (demuxFailure != null) {
                        LOG.warn("Casciian Unix-socket session {} demux failed",
                                sessionId, demuxFailure);
                    }
                } finally {
                    rawOut.flush();
                }
            }
        } catch (IOException e) {
            LOG.warn("Casciian Unix-socket session {} terminated with I/O error", sessionId, e);
        }
    }

    private void runApplication(final InputStream input,
                                final OutputStream output,
                                final SshSessionContext context) {
        try {
            final TApplication application = applicationFactory.create(input, output, context);
            if (application == null) {
                throw new IllegalStateException(
                        "CasciianTApplicationFactory returned null");
            }
            application.run();
        } catch (IOException | RuntimeException e) {
            LOG.warn("Casciian Unix-socket session for user '{}' failed",
                    context.username(), e);
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        final ServerSocketChannel channel = serverChannel.get();
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                LOG.warn("Error closing Casciian Unix-socket channel", e);
            }
        }
        final Path path = socketPath.get();
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                LOG.warn("Error deleting Casciian Unix socket file {}", path, e);
            }
        }
        serverChannel.set(null);
        socketPath.set(null);
        LOG.info("Casciian Unix-socket listener stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Mirror {@link CasciianSshServer#getPhase()}: start before, stop after,
     * the typical Spring Boot web server so operator access is available
     * for the application's entire lifetime.
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1024;
    }

    /**
     * Resolve the configured path, expanding a leading {@code ~} to the
     * user's home directory.
     */
    Path resolveSocketPath() {
        final String configured = properties.getPath();
        final String raw = (configured == null || configured.isBlank())
                ? CasciianUnixSocketProperties.DEFAULT_PATH
                : configured;
        final String expanded;
        if (raw.startsWith("~/")) {
            expanded = System.getProperty("user.home") + raw.substring(1);
        } else if (raw.equals("~")) {
            throw new IllegalStateException(
                    "Invalid socket path '~': expected a file path such as '~/casciian.sock'");
        } else {
            expanded = raw;
        }
        return Paths.get(expanded).toAbsolutePath();
    }

    private void applyPermissions(final Path path) {
        final String perms = properties.getPermissions();
        if (perms == null || perms.isBlank()) {
            return;
        }
        try {
            final Set<PosixFilePermission> set = parseOctalPermissions(perms);
            Files.setPosixFilePermissions(path, set);
        } catch (UnsupportedOperationException unsupported) {
            // Filesystem (e.g. Windows) does not support POSIX bits.
            LOG.debug("POSIX permissions not supported on {}", path);
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn("Could not apply permissions '{}' to {}: {}",
                    perms, path, e.getMessage());
        }
    }

    /**
     * Parse a 3- or 4-digit octal POSIX permission string (e.g.
     * {@code "0600"}) into a {@link Set} of {@link PosixFilePermission}.
     */
    static Set<PosixFilePermission> parseOctalPermissions(final String spec) {
        final String trimmed = spec.trim();
        if (trimmed.length() != 3 && trimmed.length() != 4) {
            throw new IllegalArgumentException(
                    "Permissions must be 3 or 4 octal digits, got '" + spec + "'");
        }
        // PosixFilePermissions.fromString needs the rwxrwxrwx form; do the
        // octal conversion by hand to keep the API surface obvious.
        final int value = Integer.parseInt(trimmed, 8);
        final int owner = (value >> 6) & 0b111;
        final int group = (value >> 3) & 0b111;
        final int other = value & 0b111;
        final char[] rwx = new char[9];
        rwx[0] = (owner & 0b100) != 0 ? 'r' : '-';
        rwx[1] = (owner & 0b010) != 0 ? 'w' : '-';
        rwx[2] = (owner & 0b001) != 0 ? 'x' : '-';
        rwx[3] = (group & 0b100) != 0 ? 'r' : '-';
        rwx[4] = (group & 0b010) != 0 ? 'w' : '-';
        rwx[5] = (group & 0b001) != 0 ? 'x' : '-';
        rwx[6] = (other & 0b100) != 0 ? 'r' : '-';
        rwx[7] = (other & 0b010) != 0 ? 'w' : '-';
        rwx[8] = (other & 0b001) != 0 ? 'x' : '-';
        return PosixFilePermissions.fromString(new String(rwx));
    }

    /** Package-private accessor for tests. */
    Path getSocketPathForTesting() {
        return socketPath.get();
    }

    /**
     * Decoded INIT frame payload. The format mirrors {@link DataInputStream}
     * conventions: two length-prefixed UTF-8 strings followed by two
     * big-endian {@code int}s.
     */
    public record InitFrame(String username, String terminalType, int columns, int rows) {

        public static InitFrame decode(final byte[] payload) throws IOException {
            try (DataInputStream in = new DataInputStream(
                    new java.io.ByteArrayInputStream(payload))) {
                final String user = in.readUTF();
                final String term = in.readUTF();
                final int cols = in.readInt();
                final int rows = in.readInt();
                return new InitFrame(user, term, cols, rows);
            }
        }
    }
}
