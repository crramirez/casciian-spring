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

/**
 * Wire protocol shared by {@link io.github.crramirez.casciian.spring.unix.CasciianUnixSocketServer} and
 * {@link CasciianConsoleClient}.
 *
 * <p>The Unix-socket channel between the in-container console client and the
 * Spring-side TUI server multiplexes three concerns on the
 * <em>client&nbsp;&rarr;&nbsp;server</em> direction:</p>
 * <ul>
 *   <li>{@link #TYPE_INIT} — single handshake message sent first by the
 *       client, carrying username, terminal type, and initial PTY
 *       geometry.</li>
 *   <li>{@link #TYPE_DATA} — raw bytes typed by the user on the local
 *       terminal, to be fed verbatim into the {@code TApplication}'s input
 *       stream.</li>
 *   <li>{@link #TYPE_RESIZE} — a {@code SIGWINCH} equivalent carrying the
 *       new column/row counts so the backend can re-render at the new
 *       size.</li>
 * </ul>
 *
 * <p>The <em>server&nbsp;&rarr;&nbsp;client</em> direction is unframed: it
 * is the raw output of Casciian's ECMA48 backend, which the client copies
 * verbatim to its standard output. This keeps the protocol trivial and
 * avoids buffering the rendered screen.</p>
 *
 * <p>Each client-to-server frame has the layout:</p>
 * <pre>
 *   +--------+----------+----------------+
 *   | type=1 | length=4 | payload=length |
 *   |  byte  | int (BE) |     bytes      |
 *   +--------+----------+----------------+
 * </pre>
 *
 * <p>Payload encodings:</p>
 * <dl>
 *   <dt>{@code INIT}</dt>
 *   <dd>{@code DataInput#readUTF()} username, {@code readUTF()} terminal
 *       type, {@code readInt()} columns, {@code readInt()} rows.</dd>
 *   <dt>{@code DATA}</dt>
 *   <dd>{@code length} raw bytes — typed characters from the local
 *       terminal.</dd>
 *   <dt>{@code RESIZE}</dt>
 *   <dd>{@code readInt()} columns, {@code readInt()} rows. Total length is
 *       always {@code 8}.</dd>
 * </dl>
 */
public final class CasciianConsoleProtocol {

    /** Handshake frame; must be the first frame sent by a connected client. */
    public static final byte TYPE_INIT = 1;

    /** A chunk of bytes typed by the user, to be forwarded to the TUI. */
    public static final byte TYPE_DATA = 2;

    /** A window-resize notification (new columns / rows). */
    public static final byte TYPE_RESIZE = 3;

    /** Maximum accepted payload length per frame, to bound memory use. */
    public static final int MAX_PAYLOAD_LENGTH = 64 * 1024;

    /** Default location of the Unix-domain socket file. */
    public static final String DEFAULT_SOCKET_PATH = "/tmp/casciian.sock";

    private CasciianConsoleProtocol() {
        // Constants only.
    }
}
