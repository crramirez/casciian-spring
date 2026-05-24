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

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tiny in-container client for the Casciian Unix-socket listener.
 *
 * <p>The class is designed to be invoked from a Spring Boot application's
 * {@code main} method <em>before</em> {@link org.springframework.boot.SpringApplication#run}
 * — typically when the user passes a dedicated argument such as
 * {@code console} to {@code java -jar app.jar console}. In that mode the
 * Spring context is <strong>not</strong> started; the JVM only opens a
 * Unix-domain socket to the already-running server process and acts as a
 * terminal multiplexer between the user's TTY and the remote TUI.</p>
 *
 * <p>The use case is connecting to a TUI exposed by an application running
 * inside a container that does not ship an SSH daemon. Operators reach the
 * container via {@code docker exec}, {@code kubectl exec}, or an ArgoCD
 * terminal, then run the bundled JAR a second time with the
 * {@code console} argument to attach to the TUI over IPC.</p>
 *
 * <p>Steps performed by {@link #run()}:</p>
 * <ol>
 *   <li>Snapshot the current terminal settings via {@code stty -g} so they
 *       can be restored on exit.</li>
 *   <li>Put the terminal in raw mode (so individual keystrokes reach the
 *       TUI without local line-editing or echo).</li>
 *   <li>Connect to the configured Unix-domain socket and send an
 *       {@link CasciianConsoleProtocol#TYPE_INIT INIT} frame with the
 *       current username, {@code $TERM}, and PTY size.</li>
 *   <li>Pump bytes both ways: stdin&nbsp;&rarr;&nbsp;socket as
 *       {@code DATA} frames, socket&nbsp;&rarr;&nbsp;stdout raw.</li>
 *   <li>Poll {@code stty size} once per second so that a window resize is
 *       forwarded to the server as a {@code RESIZE} frame.</li>
 *   <li>Restore the original terminal settings before returning.</li>
 * </ol>
 *
 * <p>The implementation deliberately depends only on {@code stty} (present
 * in every container with a userland) and the JDK; it does not pull in
 * JLine or other terminal libraries so that bundling it adds negligible
 * weight to the application JAR.</p>
 */
public final class CasciianConsoleClient {

    /** Default socket path, matching {@link CasciianConsoleProtocol#DEFAULT_SOCKET_PATH}. */
    public static final String DEFAULT_SOCKET_PATH = CasciianConsoleProtocol.DEFAULT_SOCKET_PATH;

    /** Argument value that triggers console mode in a host application's main. */
    public static final String CONSOLE_ARGUMENT = "console";

    private static final String STTY_COMMAND = "stty";

    private final Path socketPath;
    private final InputStream stdin;
    private final OutputStream stdout;
    private final SttyController stty;

    /** Build a client targeting the given socket path. */
    public CasciianConsoleClient(final Path socketPath) {
        this(socketPath, System.in, System.out, new RealSttyController());
    }

    /** Build a client using {@link #DEFAULT_SOCKET_PATH}. */
    public CasciianConsoleClient() {
        this(Paths.get(DEFAULT_SOCKET_PATH));
    }

    CasciianConsoleClient(final Path socketPath,
                          final InputStream stdin,
                          final OutputStream stdout,
                          final SttyController stty) {
        if (socketPath == null) {
            throw new IllegalArgumentException("socketPath must not be null");
        }
        this.socketPath = socketPath;
        this.stdin = stdin;
        this.stdout = stdout;
        this.stty = stty;
    }

    /**
     * Detect whether the supplied program arguments request console mode.
     * Matches the literal {@value #CONSOLE_ARGUMENT} token anywhere in the
     * argument vector, so the host application can combine it with other
     * Spring profile arguments if needed.
     */
    public static boolean isConsoleInvocation(final String[] args) {
        if (args == null) {
            return false;
        }
        for (final String a : args) {
            if (CONSOLE_ARGUMENT.equals(a)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Run the client. Blocks until the remote TUI closes the socket or the
     * local user closes stdin. The terminal is always restored to its
     * pre-invocation state, even if the connection is interrupted.
     *
     * @return an exit code suitable for {@link System#exit(int)}; {@code 0}
     *         on a clean session, non-zero if the connection failed
     */
    public int run() {
        final String saved;
        try {
            saved = stty.snapshot();
        } catch (IOException e) {
            System.err.println("casciian: could not snapshot terminal: " + e.getMessage());
            return 2;
        }
        final Thread shutdownHook = new Thread(() -> {
            try {
                stty.restore(saved);
            } catch (IOException ignored) {
                // Best effort: the user's shell will fix things up.
            }
        }, "casciian-console-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            stty.enterRawMode();
        } catch (IOException e) {
            System.err.println("casciian: could not enter raw mode: " + e.getMessage());
            return 2;
        }
        try {
            return runSession();
        } finally {
            try {
                stty.restore(saved);
            } catch (IOException e) {
                System.err.println("casciian: could not restore terminal: " + e.getMessage());
            }
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM is already shutting down; hook removal is not possible.
            }
        }
    }

    private int runSession() {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socketPath));
            try (InputStream socketIn = Channels.newInputStream(channel)) {
                final OutputStream socketOut = new BufferedOutputStream(Channels.newOutputStream(channel));
                // Serialize all writes to the socket so DATA, RESIZE and INIT
                // frames never interleave on the wire.
                final Lock writeLock = new ReentrantLock();
                sendInit(socketOut, writeLock);
                final AtomicBoolean stop = new AtomicBoolean(false);
                final Thread reader = startReader(socketIn, stop);
                final Thread resizer = startResizer(socketOut, writeLock, stop);
                try {
                    pumpStdinToSocket(socketOut, writeLock, stop);
                } finally {
                    // Half-close so the server sees EOF and tears down the TUI.
                    shutdownOutput(channel);
                    // Let the reader drain whatever the server still wants to
                    // send (e.g. final screen redraw on TUI exit). Bound the
                    // wait so a misbehaving server cannot hang the client.
                    awaitReader(reader);
                    stop.set(true);
                    resizer.interrupt();
                }
                return 0;
            }
        } catch (IOException e) {
            System.err.println("casciian: connection to " + socketPath
                    + " failed: " + e.getMessage());
            return 1;
        }
    }

    private static void shutdownOutput(final SocketChannel channel) {
        try {
            channel.shutdownOutput();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    private static void awaitReader(final Thread reader) {
        try {
            reader.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void sendInit(final OutputStream socketOut, final Lock writeLock) throws IOException {
        final ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(payload)) {
            out.writeUTF(currentUsername());
            out.writeUTF(currentTermType());
            final int[] size = stty.querySize();
            out.writeInt(size[0]);
            out.writeInt(size[1]);
        }
        writeFrame(socketOut, writeLock, CasciianConsoleProtocol.TYPE_INIT, payload.toByteArray());
    }

    private void pumpStdinToSocket(final OutputStream socketOut,
                                   final Lock writeLock,
                                   final AtomicBoolean stop) throws IOException {
        final byte[] buf = new byte[1024];
        while (!stop.get()) {
            final int n = stdin.read(buf);
            if (n < 0) {
                return;
            }
            if (n == 0) {
                continue;
            }
            final byte[] frame = new byte[n];
            System.arraycopy(buf, 0, frame, 0, n);
            writeFrame(socketOut, writeLock, CasciianConsoleProtocol.TYPE_DATA, frame);
        }
    }

    private Thread startReader(final InputStream socketIn, final AtomicBoolean stop) {
        final Thread t = new Thread(() -> {
            final byte[] buf = new byte[4096];
            try {
                while (!stop.get()) {
                    final int n = socketIn.read(buf);
                    if (n < 0) {
                        break;
                    }
                    stdout.write(buf, 0, n);
                    stdout.flush();
                }
            } catch (IOException ignored) {
                // Closed: end the session.
            } finally {
                stop.set(true);
            }
        }, "casciian-console-reader");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private Thread startResizer(final OutputStream socketOut,
                                final Lock writeLock,
                                final AtomicBoolean stop) {
        final Thread t = new Thread(() -> {
            int lastCols = -1;
            int lastRows = -1;
            while (!stop.get()) {
                try {
                    final int[] size = stty.querySize();
                    if (size[0] > 0 && size[1] > 0
                            && (size[0] != lastCols || size[1] != lastRows)) {
                        lastCols = size[0];
                        lastRows = size[1];
                        final ByteArrayOutputStream payload = new ByteArrayOutputStream();
                        try (DataOutputStream out = new DataOutputStream(payload)) {
                            out.writeInt(lastCols);
                            out.writeInt(lastRows);
                        }
                        writeFrame(socketOut, writeLock,
                                CasciianConsoleProtocol.TYPE_RESIZE, payload.toByteArray());
                    }
                } catch (IOException e) {
                    return;
                }
                try {
                    // Intentional low-frequency polling: POSIX terminals do not
                    // provide a portable resize event to this in-container client.
                    //noinspection BusyWait
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "casciian-console-resizer");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void writeFrame(final OutputStream out,
                                   final Lock writeLock,
                                   final byte type,
                                   final byte[] payload) throws IOException {
        writeLock.lock();
        try {
            out.write(type);
            // Big-endian length, matching DataInput.readInt() on the server.
            out.write((payload.length >>> 24) & 0xFF);
            out.write((payload.length >>> 16) & 0xFF);
            out.write((payload.length >>> 8) & 0xFF);
            out.write(payload.length & 0xFF);
            out.write(payload);
            out.flush();
        } finally {
            writeLock.unlock();
        }
    }

    private static String currentUsername() {
        final String user = System.getProperty("user.name");
        return user == null ? "" : user;
    }

    private static String currentTermType() {
        final String term = System.getenv("TERM");
        return term == null ? "" : term;
    }

    // ------------------------------------------------------------------
    // Terminal abstraction
    // ------------------------------------------------------------------

    /**
     * Encapsulates the few {@code stty} operations the client needs.
     * Pulled out as an interface so unit tests can substitute a fake
     * implementation and avoid running real {@code stty} processes.
     */
    interface SttyController {

        /**
         * Capture the current terminal settings (the output of
         * {@code stty -g}) so they can be passed back to
         * {@link #restore(String)} later.
         */
        String snapshot() throws IOException;

        /** Put the terminal into raw, no-echo, no-isig mode. */
        void enterRawMode() throws IOException;

        /**
         * Restore the terminal to the settings captured by a previous
         * {@link #snapshot()} call.
         */
        void restore(String snapshot) throws IOException;

        /**
         * Query the current terminal size.
         *
         * @return a 2-element array {@code [columns, rows]}; both
         *         elements are {@code 0} when the size cannot be
         *         determined
         */
        int[] querySize() throws IOException;
    }

    /**
     * Real {@link SttyController} backed by the system {@code stty}
     * binary. Each operation forks a short-lived process; the work is
     * negligible on the polling cadence used by the client (1Hz at most).
     */
    private static final class RealSttyController implements SttyController {

        @Override
        public String snapshot() throws IOException {
            return runSttyForOutput("-g").trim();
        }

        @Override
        public void enterRawMode() throws IOException {
            runStty("raw", "-echo", "-isig");
        }

        @Override
        public void restore(final String snapshot) throws IOException {
            if (snapshot == null || snapshot.isBlank()) {
                return;
            }
            runStty(snapshot);
        }

        @Override
        public int[] querySize() throws IOException {
            // `stty size` prints "rows cols".
            final String out = runSttyForOutput("size").trim();
            if (out.isEmpty()) {
                return new int[] { 0, 0 };
            }
            final String[] parts = out.split("\\s+");
            if (parts.length < 2) {
                return new int[] { 0, 0 };
            }
            try {
                final int rows = Integer.parseInt(parts[0]);
                final int cols = Integer.parseInt(parts[1]);
                return new int[] { cols, rows };
            } catch (NumberFormatException e) {
                return new int[] { 0, 0 };
            }
        }

        private static void runStty(final String... args) throws IOException {
            // We invoke stty via `sh -c` and redirect from /dev/tty so the
            // command always targets the controlling terminal, even if the
            // calling JVM has its standard streams attached to pipes (as
            // is the case under `docker exec -t`).
            final StringBuilder cmd = new StringBuilder(STTY_COMMAND);
            for (final String a : args) {
                cmd.append(' ').append(shellQuote(a));
            }
            cmd.append(" </dev/tty");
            final Process p = new ProcessBuilder("sh", "-c", cmd.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            awaitOrFail(p, STTY_COMMAND + " " + args[0]);
        }

        private static String runSttyForOutput(final String arg) throws IOException {
            final String cmd = STTY_COMMAND + " " + shellQuote(arg) + " </dev/tty";
            final Process p = new ProcessBuilder("sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();
            final String output = new String(p.getInputStream().readAllBytes());
            awaitOrFail(p, STTY_COMMAND + " " + arg);
            return output;
        }

        private static void awaitOrFail(final Process p, final String label) throws IOException {
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    throw new IOException(label + " timed out");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException(label + " interrupted", ie);
            }
            if (p.exitValue() != 0) {
                throw new IOException(label + " exited with " + p.exitValue());
            }
        }

        /**
         * Quote a single shell argument so spaces and other metacharacters
         * are passed through to {@code stty} verbatim. We deliberately
         * keep the alphabet small: {@code stty -g} output is composed of
         * {@code [A-Za-z0-9:_,-]} characters, well within what single
         * quotes will protect.
         */
        private static String shellQuote(final String arg) {
            return "'" + arg.replace("'", "'\\''") + "'";
        }
    }
}
