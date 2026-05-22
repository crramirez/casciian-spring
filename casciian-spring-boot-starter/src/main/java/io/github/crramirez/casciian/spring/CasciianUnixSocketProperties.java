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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Casciian Unix-domain-socket listener.
 *
 * <p>Bind under the {@code casciian.unix-socket} prefix in
 * {@code application.yml} or {@code application.properties}. The Unix-socket
 * listener is the lightweight alternative to the SSH server intended for
 * use inside containers where {@code docker exec}, {@code kubectl exec},
 * or an ArgoCD terminal already provide an authenticated shell.</p>
 *
 * <p>Defaults are conservative: the listener is <em>disabled by default</em>
 * because it places a filesystem object inside the running container.
 * Enable it explicitly with {@code casciian.unix-socket.enabled=true}.</p>
 */
@ConfigurationProperties(prefix = "casciian.unix-socket")
public class CasciianUnixSocketProperties {

    /** Default location of the Unix-domain socket file. */
    public static final String DEFAULT_PATH = "/tmp/casciian.sock";

    /**
     * POSIX-style permission string (3 octal digits) applied to the socket
     * file after binding. {@code "600"} restricts the socket to the running
     * user, which matches the threat model of container-local IPC.
     */
    public static final String DEFAULT_PERMISSIONS = "600";

    /**
     * Whether the Unix-socket listener should start. Defaults to
     * {@code false} so that adding the starter to an existing application
     * does not silently expose a new on-disk endpoint.
     */
    private boolean enabled = false;

    /**
     * Filesystem path where the Unix-domain socket file is created. A
     * leading {@code ~} is expanded to the user home directory. The parent
     * directory must exist or be creatable.
     */
    private String path = DEFAULT_PATH;

    /**
     * POSIX file permissions for the socket file, written as 3 or 4 octal
     * digits (e.g. {@code "600"} or {@code "0600"}). Ignored on file
     * systems that do not support POSIX permissions.
     */
    private String permissions = DEFAULT_PERMISSIONS;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public String getPath() {
        return path;
    }

    public void setPath(final String path) {
        this.path = path;
    }

    public String getPermissions() {
        return permissions;
    }

    public void setPermissions(final String permissions) {
        this.permissions = permissions;
    }
}
