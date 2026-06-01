/*
 * Copyright 2026 Carlos Rafael Ramirez
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.crramirez.casciian.spring.unix;

import io.github.crramirez.casciian.spring.CasciianTApplicationFactory;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import casciian.TApplication;

/**
 * Spring Boot auto-configuration that wires the Unix-domain-socket variant
 * of the Casciian listener.
 *
 * <p>The configuration is independent of {@link io.github.crramirez.casciian.spring.ssh.CasciianSshAutoConfiguration}
 * so the two listeners can be enabled together, separately, or both
 * disabled. Both rely on the same user-supplied
 * {@link CasciianTApplicationFactory} bean — there is one TUI definition,
 * exposed through whichever transport(s) the operator picks.</p>
 *
 * <p>Active when:</p>
 * <ul>
 *   <li>{@link TApplication} is on the classpath.</li>
 *   <li>Property {@code casciian.unix-socket.enabled=true} is set
 *       explicitly. The default is {@code false} because the listener
 *       creates a file inside the running container and operators should
 *       opt in.</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass(TApplication.class)
@ConditionalOnProperty(prefix = "casciian.unix-socket", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CasciianUnixSocketProperties.class)
public class CasciianUnixSocketAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CasciianUnixSocketServer casciianUnixSocketServer(
            final CasciianUnixSocketProperties properties,
            final CasciianTApplicationFactory applicationFactory) {
        return new CasciianUnixSocketServer(properties, applicationFactory);
    }
}
