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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import casciian.TApplication;
import casciian.backend.SessionInfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CasciianUnixSocketServer}: socket-path resolution,
 * permission parsing, and a round-trip handshake against a real
 * Unix-domain socket so the wire protocol stays end-to-end correct.
 */
class CasciianUnixSocketServerTest {

    @TempDir
    Path tmp;

    @Test
    void resolvesAbsolutePathUnchanged() {
        final CasciianUnixSocketProperties props = new CasciianUnixSocketProperties();
        final Path target = tmp.resolve("a.sock");
        props.setPath(target.toString());
        final CasciianUnixSocketServer server = new CasciianUnixSocketServer(
                props, (in, out, session) -> mock(TApplication.class));
        assertThat(server.resolveSocketPath()).isEqualTo(target.toAbsolutePath());
    }

    @Test
    void expandsTildeAgainstUserHome() {
        final String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.toString());
        try {
            final CasciianUnixSocketProperties props = new CasciianUnixSocketProperties();
            props.setPath("~/socks/x.sock");
            final CasciianUnixSocketServer server = new CasciianUnixSocketServer(
                    props, (in, out, session) -> mock(TApplication.class));
            assertThat(server.resolveSocketPath().toString())
                    .startsWith(tmp.toString())
                    .endsWith("x.sock");
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void parsesOctalPermissions() {
        final Set<PosixFilePermission> p = CasciianUnixSocketServer.parseOctalPermissions("600");
        assertThat(p).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

        final Set<PosixFilePermission> p644 = CasciianUnixSocketServer.parseOctalPermissions("0644");
        assertThat(p644).contains(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ);
    }

    @Test
    void rejectsMalformedPermissions() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CasciianUnixSocketServer.parseOctalPermissions("rwx"));
    }

    @Test
    void endToEndHandshakeAndDataExchange() throws Exception {
        final Path socket = tmp.resolve("casciian.sock");
        final CasciianUnixSocketProperties props = new CasciianUnixSocketProperties();
        props.setEnabled(true);
        props.setPath(socket.toString());
        props.setPermissions("600");

        final CountDownLatch sessionReady = new CountDownLatch(1);
        final AtomicReference<SshSessionContext> capturedContext = new AtomicReference<>();
        final AtomicReference<SessionInfo> capturedSessionInfo = new AtomicReference<>();
        final AtomicReference<byte[]> capturedInput = new AtomicReference<>();

        final CasciianTApplicationFactory factory = (in, out, session) -> {
            capturedContext.set(session);
            capturedSessionInfo.set((SessionInfo) in);
            final TApplication app = mock(TApplication.class);
            when(app.toString()).thenReturn("stub");
            // The factory runs on the per-session virtual thread. Read the
            // expected payload, write a reply, and let run() be a no-op
            // (Mockito returns immediately).
            final byte[] buf = new byte[5];
            int read = 0;
            while (read < buf.length) {
                final int n = in.read(buf, read, buf.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            capturedInput.set(java.util.Arrays.copyOf(buf, read));
            out.write("PONG\n".getBytes());
            out.flush();
            sessionReady.countDown();
            return app;
        };

        final CasciianUnixSocketServer server = new CasciianUnixSocketServer(props, factory);
        server.start();
        try {
            assertThat(server.isRunning()).isTrue();
            assertThat(Files.exists(socket)).isTrue();

            try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                ch.connect(UnixDomainSocketAddress.of(socket));
                final OutputStream out = Channels.newOutputStream(ch);
                final InputStream in = Channels.newInputStream(ch);

                // INIT frame.
                final java.io.ByteArrayOutputStream payload = new java.io.ByteArrayOutputStream();
                try (DataOutputStream dout = new DataOutputStream(payload)) {
                    dout.writeUTF("alice");
                    dout.writeUTF("xterm-256color");
                    dout.writeInt(120);
                    dout.writeInt(40);
                }
                writeFrame(out, CasciianConsoleProtocol.TYPE_INIT, payload.toByteArray());
                // DATA frame.
                writeFrame(out, CasciianConsoleProtocol.TYPE_DATA, "HELLO".getBytes());

                // Read the server's reply.
                final DataInputStream din = new DataInputStream(in);
                final byte[] reply = new byte[5];
                din.readFully(reply);
                assertThat(new String(reply)).isEqualTo("PONG\n");

                assertThat(sessionReady.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(capturedContext.get().username()).isEqualTo("alice");
                assertThat(capturedContext.get().terminalType()).isEqualTo("xterm-256color");
                assertThat(capturedContext.get().columns()).isEqualTo(120);
                assertThat(capturedContext.get().rows()).isEqualTo(40);
                assertThat(capturedContext.get().remoteAddress())
                        .startsWith("unix:")
                        .contains("casciian.sock");
                assertThat(new String(capturedInput.get())).isEqualTo("HELLO");
                assertThat(capturedSessionInfo.get().getWindowWidth()).isEqualTo(120);
                assertThat(capturedSessionInfo.get().getWindowHeight()).isEqualTo(40);
            }
        } finally {
            server.stop();
        }
        assertThat(server.isRunning()).isFalse();
        // Socket file removed on shutdown.
        assertThat(Files.exists(socket)).isFalse();
    }

    @Test
    void resizeFramePropagatesToSessionInfo() throws Exception {
        final Path socket = tmp.resolve("resize.sock");
        final CasciianUnixSocketProperties props = new CasciianUnixSocketProperties();
        props.setEnabled(true);
        props.setPath(socket.toString());

        final CountDownLatch resized = new CountDownLatch(1);
        final AtomicReference<SessionInfo> sessionInfo = new AtomicReference<>();

        final CasciianTApplicationFactory factory = (in, out, session) -> {
            sessionInfo.set((SessionInfo) in);
            // Keep the session open until the test fires the resize signal
            // and we observe the new size on the SessionInfo.
            new Thread(() -> {
                try {
                    for (int i = 0; i < 200; i++) {
                        if (((SessionInfo) in).getWindowWidth() == 200) {
                            resized.countDown();
                            return;
                        }
                        Thread.sleep(20);
                    }
                } catch (InterruptedException ignored) {
                    // ignored
                }
            }, "size-watcher").start();
            return mock(TApplication.class);
        };

        final CasciianUnixSocketServer server = new CasciianUnixSocketServer(props, factory);
        server.start();
        try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            ch.connect(UnixDomainSocketAddress.of(socket));
            final OutputStream out = Channels.newOutputStream(ch);
            final java.io.ByteArrayOutputStream payload = new java.io.ByteArrayOutputStream();
            try (DataOutputStream dout = new DataOutputStream(payload)) {
                dout.writeUTF("bob");
                dout.writeUTF("");
                dout.writeInt(80);
                dout.writeInt(24);
            }
            writeFrame(out, CasciianConsoleProtocol.TYPE_INIT, payload.toByteArray());

            // Send a RESIZE frame.
            final java.io.ByteArrayOutputStream rp = new java.io.ByteArrayOutputStream();
            try (DataOutputStream dout = new DataOutputStream(rp)) {
                dout.writeInt(200);
                dout.writeInt(60);
            }
            writeFrame(out, CasciianConsoleProtocol.TYPE_RESIZE, rp.toByteArray());

            assertThat(resized.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(sessionInfo.get().getWindowWidth()).isEqualTo(200);
            assertThat(sessionInfo.get().getWindowHeight()).isEqualTo(60);
        } finally {
            server.stop();
        }
    }

    @Test
    void stopRemovesSocketFileEvenWhenStaleEntryExisted() throws Exception {
        final Path socket = tmp.resolve("stale.sock");
        // Pretend a previous run left a regular file (or socket) behind.
        Files.writeString(socket, "stale");

        final CasciianUnixSocketProperties props = new CasciianUnixSocketProperties();
        props.setEnabled(true);
        props.setPath(socket.toString());
        final CasciianUnixSocketServer server = new CasciianUnixSocketServer(
                props, (in, out, session) -> mock(TApplication.class));
        server.start();
        try {
            assertThat(Files.exists(socket)).isTrue();
        } finally {
            server.stop();
        }
        assertThat(Files.exists(socket)).isFalse();
    }

    private static void writeFrame(final OutputStream out, final byte type, final byte[] payload) throws IOException {
        out.write(type);
        out.write((payload.length >>> 24) & 0xFF);
        out.write((payload.length >>> 16) & 0xFF);
        out.write((payload.length >>> 8) & 0xFF);
        out.write(payload.length & 0xFF);
        out.write(payload);
        out.flush();
    }
}
