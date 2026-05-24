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
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import casciian.backend.SessionInfo;

/**
 * {@link InputStream} adapter for the Unix-socket transport that demultiplexes
 * {@link CasciianConsoleProtocol} frames into a single byte stream and
 * exposes the negotiated PTY geometry via Casciian's {@link SessionInfo}.
 *
 * <p>A dedicated reader thread reads framed messages from the socket,
 * writes {@code DATA} payloads into an internal pipe (read by Casciian's
 * ECMA48 backend) and applies {@code RESIZE} frames to the cached window
 * size — which the backend polls once per second to emit
 * {@code TResizeEvent}s.</p>
 *
 * <p>Just like {@link SshSessionInfoInputStream}, width and height are
 * packed into a single {@code volatile long} so concurrent readers always
 * see a consistent (width, height) pair.</p>
 */
final class UnixSessionInfoInputStream extends InputStream implements SessionInfo {

    /** Default fallback width when the client never advertises a PTY size. */
    static final int DEFAULT_WINDOW_WIDTH = 80;

    /** Default fallback height when the client never advertises a PTY size. */
    static final int DEFAULT_WINDOW_HEIGHT = 24;

    private final long startTime = System.currentTimeMillis();
    private final PipedInputStream pipe;
    private final PipedOutputStream pipeSink;
    private final Thread demuxThread;

    private volatile String username;
    private volatile String language = "en_US";
    private final AtomicLong windowSize = new AtomicLong();
    private volatile int idleTime = Integer.MAX_VALUE;
    private final AtomicReference<IOException> demuxFailure = new AtomicReference<>();

    /**
     * Build a wrapper around an already-handshaked socket input stream.
     *
     * @param socketInput   the socket's raw input stream, positioned
     *                      <em>after</em> the {@code INIT} frame has been
     *                      consumed by the server
     * @param initialUser   the username carried in the initial handshake
     * @param initialCols   the initial PTY width (falls back to
     *                      {@value #DEFAULT_WINDOW_WIDTH} if &le; 0)
     * @param initialRows   the initial PTY height (falls back to
     *                      {@value #DEFAULT_WINDOW_HEIGHT} if &le; 0)
     * @param threadName    descriptive name for the demux thread; useful in
     *                      thread dumps
     * @throws IOException  if the internal pipe cannot be wired
     */
    UnixSessionInfoInputStream(final InputStream socketInput,
                               final String initialUser,
                               final int initialCols,
                               final int initialRows,
                               final String threadName) throws IOException {
        if (socketInput == null) {
            throw new IllegalArgumentException("socketInput must not be null");
        }
        this.username = initialUser == null ? "" : initialUser;
        final int w = initialCols > 0 ? initialCols : DEFAULT_WINDOW_WIDTH;
        final int h = initialRows > 0 ? initialRows : DEFAULT_WINDOW_HEIGHT;
        this.windowSize.set(pack(w, h));
        // Generous buffer so a burst of pasted input does not block the
        // demux thread (which would also block out-of-band RESIZE frames).
        this.pipe = new PipedInputStream(64 * 1024);
        this.pipeSink = new PipedOutputStream(pipe);
        this.demuxThread = Thread.ofPlatform()
                .name(threadName == null ? "casciian-unix-demux" : threadName)
                .daemon(true)
                .unstarted(() -> demuxLoop(socketInput));
        this.demuxThread.start();
    }

    /**
     * Read framed messages from the socket forever, applying each frame's
     * effect (push DATA into the pipe, update window size on RESIZE) until
     * the socket reaches EOF or an I/O error occurs. Closing the pipe sink
     * surfaces as {@code read() == -1} on the Casciian side, which is what
     * we want when the client disconnects.
     */
    private void demuxLoop(final InputStream socketInput) {
        try (DataInputStream in = new DataInputStream(socketInput)) {
            while (true) {
                final int type = readFrameType(in);
                if (type < 0) {
                    break;
                }
                final int length = in.readInt();
                if (length < 0 || length > CasciianConsoleProtocol.MAX_PAYLOAD_LENGTH) {
                    throw new IOException(
                            "Frame payload length " + length + " out of range");
                }
                switch (type) {
                    case CasciianConsoleProtocol.TYPE_DATA ->
                        // Stream the payload to the pipe in chunks to avoid
                        // allocating a large buffer for the worst case.
                        copyToPipe(in, length);
                    case CasciianConsoleProtocol.TYPE_RESIZE -> {
                        if (length != 8) {
                            throw new IOException(
                                    "RESIZE frame must carry exactly 8 bytes, got " + length);
                        }
                        final int cols = in.readInt();
                        final int rows = in.readInt();
                        setWindowSize(cols, rows);
                    }
                    default ->
                        // Unknown frame type. Skip to keep the stream in
                        // sync rather than silently desyncing.
                        in.skipNBytes(length);
                }
            }
        } catch (IOException e) {
            this.demuxFailure.set(e);
        } finally {
            try {
                pipeSink.close();
            } catch (IOException ignored) {
                // Closing is best-effort: the read side will see EOF.
            }
        }
    }

    private static int readFrameType(final DataInputStream in) throws IOException {
        try {
            return in.readByte();
        } catch (EOFException eof) {
            return -1;
        }
    }

    private void copyToPipe(final DataInputStream in, final int length) throws IOException {
        final byte[] buf = new byte[Math.min(length, 4096)];
        int remaining = length;
        while (remaining > 0) {
            final int chunk = Math.min(buf.length, remaining);
            in.readFully(buf, 0, chunk);
            pipeSink.write(buf, 0, chunk);
            remaining -= chunk;
        }
        pipeSink.flush();
    }

    /**
     * Apply a new PTY geometry pushed by the client. Non-positive values
     * are ignored so a malformed RESIZE frame cannot collapse the screen.
     */
    void setWindowSize(final int columns, final int rows) {
        windowSize.updateAndGet(current -> {
            final int newWidth = columns > 0 ? columns : unpackWidth(current);
            final int newHeight = rows > 0 ? rows : unpackHeight(current);
            return pack(newWidth, newHeight);
        });
    }

    private static long pack(final int width, final int height) {
        return ((long) width << 32) | (height & 0xFFFFFFFFL);
    }

    private static int unpackWidth(final long packed) {
        return (int) (packed >>> 32);
    }

    private static int unpackHeight(final long packed) {
        return (int) packed;
    }

    // ------------------------------------------------------------------
    // InputStream
    // ------------------------------------------------------------------

    @Override
    public int read() throws IOException {
        return pipe.read();
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        return pipe.read(b, off, len);
    }

    @Override
    public int available() throws IOException {
        return pipe.available();
    }

    @Override
    public void close() throws IOException {
        pipe.close();
        demuxThread.interrupt();
    }

    /** Visible for tests: the most recent demux failure, if any. */
    IOException getDemuxFailureForTesting() {
        return demuxFailure.get();
    }

    // ------------------------------------------------------------------
    // SessionInfo
    // ------------------------------------------------------------------

    @Override
    public long getStartTime() {
        return startTime;
    }

    @Override
    public int getIdleTime() {
        return idleTime;
    }

    @Override
    public void setIdleTime(final int seconds) {
        this.idleTime = seconds;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public void setUsername(final String username) {
        this.username = username == null ? "" : username;
    }

    @Override
    public String getLanguage() {
        return language;
    }

    @Override
    public void setLanguage(final String language) {
        this.language = language == null ? "en_US" : language;
    }

    @Override
    public int getWindowWidth() {
        return unpackWidth(windowSize.get());
    }

    @Override
    public int getWindowHeight() {
        return unpackHeight(windowSize.get());
    }

    /**
     * No-op: the size is push-updated by RESIZE frames from the client, so
     * there is nothing to query on demand.
     */
    @Override
    public void queryWindowSize() {
        // Intentionally empty.
    }
}
