/*
 * Copyright 2026 Carlos Rafael Ramirez
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.crramirez.casciian.demo.shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import io.github.crramirez.casciian.spring.CasciianConsoleClient;

/**
 * Entry point for the demo shop. The application exposes:
 *
 * <ul>
 *   <li>A customer-facing product catalogue at <code>http://localhost:8080/</code>
 *       backed by an in-memory H2 database seeded with random products.</li>
 *   <li>A Casciian admin TUI reachable over SSH at port 2222 that allows
 *       full CRUD on the same product catalogue.</li>
 *   <li>The same admin TUI reachable over a Unix-domain socket at
 *       {@code /tmp/casciian.sock} so operators inside the container
 *       (reached via {@code docker exec}, {@code kubectl exec}, or an
 *       ArgoCD terminal) can attach without needing SSH.</li>
 * </ul>
 *
 * <p>Passing the literal argument {@value CasciianConsoleClient#CONSOLE_ARGUMENT}
 * to {@code main} switches the JAR into <em>console-client mode</em>: it
 * does not boot Spring at all, only connects to the local Unix socket and
 * acts as a thin terminal multiplexer between the user's TTY and the
 * already-running server process.</p>
 *
 * <p>The whole point of this demo is to show that a single Spring Boot
 * application can serve both audiences from one JVM, sharing the same
 * Spring beans (repositories, services, security context).</p>
 */
@SpringBootApplication
public class DemoShopApplication {

    /**
     * Standard Spring Boot main method.
     *
     * <p>When invoked with {@code "console"} as one of the program
     * arguments, the method delegates to {@link CasciianConsoleClient}
     * and returns without starting the Spring context. This is the
     * "attach to the in-container TUI over IPC" code path.</p>
     *
     * @param args command line arguments
     */
    public static void main(final String[] args) {
        if (CasciianConsoleClient.isConsoleInvocation(args)) {
            final int exitCode = new CasciianConsoleClient().run();
            if (exitCode != 0) {
                System.exit(exitCode);
            }
            return;
        }
        SpringApplication.run(DemoShopApplication.class, args);
    }
}
