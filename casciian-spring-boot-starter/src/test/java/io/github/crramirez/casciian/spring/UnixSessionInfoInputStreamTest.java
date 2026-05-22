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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Black-box tests for {@link UnixSessionInfoInputStream}: the demux loop
 * must surface DATA payloads on the read side, apply RESIZE frames to the
 * cached window geometry, and reach EOF cleanly when the socket closes.
 */
class UnixSessionInfoInputStreamTest {

    @Test
    void demultiplexesDataFramesIntoTheReadSide() throws Exception {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            writeData(out, "hello".getBytes());
            writeData(out, " world".getBytes());
        }

        try (UnixSessionInfoInputStream in = new UnixSessionInfoInputStream(
                new ByteArrayInputStream(raw.toByteArray()), "alice", 100, 30, "test")) {
            final byte[] buf = new byte["hello world".length()];
            int read = 0;
            while (read < buf.length) {
                final int n = in.read(buf, read, buf.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            assertThat(new String(buf, 0, read)).isEqualTo("hello world");
            assertThat(in.read()).isEqualTo(-1);
        }
    }

    @Test
    void resizeFrameUpdatesWindowSize() throws Exception {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            writeResize(out, 132, 50);
            writeData(out, "x".getBytes());
        }

        try (UnixSessionInfoInputStream in = new UnixSessionInfoInputStream(
                new ByteArrayInputStream(raw.toByteArray()), "bob", 80, 24, "test")) {
            // Block until the data frame arrives so we know the demux
            // thread already processed the RESIZE frame above it.
            assertThat(in.read()).isEqualTo((int) 'x');
            assertThat(in.getWindowWidth()).isEqualTo(132);
            assertThat(in.getWindowHeight()).isEqualTo(50);
        }
    }

    @Test
    void fallsBackToDefaultWindowSizeOnNonPositiveInitialValues() throws Exception {
        try (UnixSessionInfoInputStream in = new UnixSessionInfoInputStream(
                new ByteArrayInputStream(new byte[0]), "", 0, -5, "test")) {
            assertThat(in.getWindowWidth()).isEqualTo(UnixSessionInfoInputStream.DEFAULT_WINDOW_WIDTH);
            assertThat(in.getWindowHeight()).isEqualTo(UnixSessionInfoInputStream.DEFAULT_WINDOW_HEIGHT);
        }
    }

    @Test
    void rejectsOversizedPayloads() throws Exception {
        // Hand-craft a frame whose declared length exceeds MAX_PAYLOAD_LENGTH.
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(CasciianConsoleProtocol.TYPE_DATA);
            out.writeInt(CasciianConsoleProtocol.MAX_PAYLOAD_LENGTH + 1);
        }
        try (UnixSessionInfoInputStream in = new UnixSessionInfoInputStream(
                new ByteArrayInputStream(raw.toByteArray()), "", 80, 24, "test")) {
            // EOF surfaces immediately on the pipe side and the failure is
            // recorded so the server can log it.
            assertThat(in.read()).isEqualTo(-1);
            // Give the demux thread a beat to record the failure.
            for (int i = 0; i < 50 && in.getDemuxFailureForTesting() == null; i++) {
                Thread.sleep(20);
            }
            assertThat(in.getDemuxFailureForTesting())
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("out of range");
        }
    }

    @Test
    void ignoresUnknownFrameTypes() throws Exception {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte((byte) 99); // unknown type
            out.writeInt(3);
            out.write(new byte[] { 'a', 'b', 'c' });
            writeData(out, "ok".getBytes());
        }
        try (UnixSessionInfoInputStream in = new UnixSessionInfoInputStream(
                new ByteArrayInputStream(raw.toByteArray()), "", 80, 24, "test")) {
            final byte[] buf = new byte[2];
            int read = 0;
            while (read < buf.length) {
                final int n = in.read(buf, read, buf.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            assertThat(new String(buf, 0, read)).isEqualTo("ok");
        }
    }

    private static void writeData(final DataOutputStream out, final byte[] payload) throws IOException {
        out.writeByte(CasciianConsoleProtocol.TYPE_DATA);
        out.writeInt(payload.length);
        out.write(payload);
    }

    private static void writeResize(final DataOutputStream out, final int cols, final int rows) throws IOException {
        out.writeByte(CasciianConsoleProtocol.TYPE_RESIZE);
        out.writeInt(8);
        out.writeInt(cols);
        out.writeInt(rows);
    }
}
