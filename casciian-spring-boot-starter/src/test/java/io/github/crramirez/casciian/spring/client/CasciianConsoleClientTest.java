/*
 * Copyright 2026 Carlos Rafael Ramirez
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.crramirez.casciian.spring.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.crramirez.casciian.spring.unix.CasciianUnixSocketServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CasciianConsoleClient}: argument detection, terminal
 * lifecycle ordering, and an end-to-end exchange against a hand-rolled
 * Unix-socket server.
 */
class CasciianConsoleClientTest {

    @TempDir
    Path tmp;

    @Test
    void detectsConsoleArgument() {
        assertThat(CasciianConsoleClient.isConsoleInvocation(new String[] { "console" })).isTrue();
        assertThat(CasciianConsoleClient.isConsoleInvocation(new String[] { "--server.port=0", "console" })).isTrue();
        assertThat(CasciianConsoleClient.isConsoleInvocation(new String[] {})).isFalse();
        assertThat(CasciianConsoleClient.isConsoleInvocation(null)).isFalse();
        assertThat(CasciianConsoleClient.isConsoleInvocation(new String[] { "CONSOLE" })).isFalse();
    }

    @Test
    void restoresTerminalAfterSuccessfulSession() throws Exception {
        final Path socket = tmp.resolve("c.sock");
        final FakeStty stty = new FakeStty("ORIG", new int[] { 80, 24 });
        final ByteArrayInputStream stdin = new ByteArrayInputStream("typed".getBytes());
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));

            final CountDownLatch initSeen = new CountDownLatch(1);
            final CountDownLatch dataSeen = new CountDownLatch(1);
            final AtomicReference<CasciianUnixSocketServer.InitFrame> initFrame = new AtomicReference<>();
            final AtomicReference<byte[]> dataPayload = new AtomicReference<>();

            final Thread acceptor = new Thread(() -> {
                try (SocketChannel ch = server.accept()) {
                    final DataInputStream in = new DataInputStream(Channels.newInputStream(ch));
                    // INIT
                    assertThat(in.readByte()).isEqualTo(CasciianConsoleProtocol.TYPE_INIT);
                    int len = in.readInt();
                    final byte[] payload = new byte[len];
                    in.readFully(payload);
                    initFrame.set(CasciianUnixSocketServer.InitFrame.decode(payload));
                    initSeen.countDown();
                    // DATA
                    assertThat(in.readByte()).isEqualTo(CasciianConsoleProtocol.TYPE_DATA);
                    len = in.readInt();
                    final byte[] data = new byte[len];
                    in.readFully(data);
                    dataPayload.set(data);
                    dataSeen.countDown();
                    // Send something back to the client; then close.
                    Channels.newOutputStream(ch).write("BACK".getBytes());
                } catch (IOException ignored) {
                    // closing is expected at end of test
                }
            }, "fake-server");
            acceptor.start();

            final CasciianConsoleClient client = new CasciianConsoleClient(socket, stdin, stdout, stty);
            final int exit = client.run();

            acceptor.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(exit).isZero();
            assertThat(initSeen.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(initFrame.get().columns()).isEqualTo(80);
            assertThat(initFrame.get().rows()).isEqualTo(24);
            assertThat(dataSeen.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(new String(dataPayload.get())).isEqualTo("typed");
            assertThat(stdout.toString()).contains("BACK");

            // Terminal lifecycle: snapshot, then enter raw mode, then restore.
            assertThat(stty.calls).containsExactly("snapshot", "enterRawMode", "restore:ORIG");
        }
    }

    @Test
    void reportsErrorWhenSnapshotFails() {
        final FakeStty stty = new FakeStty(null, new int[] { 80, 24 }) {
            @Override
            public String snapshot() throws IOException {
                throw new IOException("no tty");
            }
        };
        final CasciianConsoleClient client = new CasciianConsoleClient(
                tmp.resolve("nope.sock"),
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(),
                stty);
        assertThat(client.run()).isEqualTo(2);
    }

    @Test
    void reportsErrorWhenSocketIsMissing() {
        final FakeStty stty = new FakeStty("S", new int[] { 80, 24 });
        final CasciianConsoleClient client = new CasciianConsoleClient(
                tmp.resolve("missing.sock"),
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(),
                stty);
        assertThat(client.run()).isEqualTo(1);
        // The terminal must still be restored even though the connection
        // failed mid-run.
        assertThat(stty.calls).containsSubsequence("snapshot", "enterRawMode", "restore:S");
    }

    private static class FakeStty implements CasciianConsoleClient.SttyController {

        final List<String> calls = new ArrayList<>();
        private final String snapshot;
        private final int[] size;

        FakeStty(final String snapshot, final int[] size) {
            this.snapshot = snapshot;
            this.size = size;
        }

        @Override
        public String snapshot() throws IOException {
            calls.add("snapshot");
            return snapshot;
        }

        @Override
        public void enterRawMode() {
            calls.add("enterRawMode");
        }

        @Override
        public void restore(final String snap) {
            calls.add("restore:" + snap);
        }

        @Override
        public int[] querySize() {
            return new int[] { size[0], size[1] };
        }
    }
}
