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

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import casciian.TApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Black-box tests for {@link CasciianUnixSocketAutoConfiguration}.
 */
class CasciianUnixSocketAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CasciianUnixSocketAutoConfiguration.class));

    @Test
    void doesNotLoadByDefault() {
        runner.withUserConfiguration(FactoryConfig.class).run(context -> {
            assertThat(context).doesNotHaveBean(CasciianUnixSocketServer.class);
        });
    }

    @Test
    void doesNotLoadWhenExplicitlyDisabled() {
        runner.withUserConfiguration(FactoryConfig.class)
                .withPropertyValues("casciian.unix-socket.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CasciianUnixSocketServer.class);
                });
    }

    @Test
    void loadsAndBindsPropertiesWhenEnabled() {
        runner.withUserConfiguration(FactoryConfig.class)
                .withPropertyValues(
                        "casciian.unix-socket.enabled=true",
                        "casciian.unix-socket.path=/tmp/test.sock",
                        "casciian.unix-socket.permissions=600")
                .run(context -> {
                    assertThat(context).hasSingleBean(CasciianUnixSocketServer.class);
                    final CasciianUnixSocketProperties props =
                            context.getBean(CasciianUnixSocketProperties.class);
                    assertThat(props.isEnabled()).isTrue();
                    assertThat(props.getPath()).isEqualTo("/tmp/test.sock");
                    assertThat(props.getPermissions()).isEqualTo("600");
                });
    }

    @Test
    void usesDefaultsWhenOnlyEnabledFlagIsSet() {
        runner.withUserConfiguration(FactoryConfig.class)
                .withPropertyValues("casciian.unix-socket.enabled=true")
                .run(context -> {
                    final CasciianUnixSocketProperties props =
                            context.getBean(CasciianUnixSocketProperties.class);
                    assertThat(props.getPath()).isEqualTo(CasciianUnixSocketProperties.DEFAULT_PATH);
                    assertThat(props.getPermissions())
                            .isEqualTo(CasciianUnixSocketProperties.DEFAULT_PERMISSIONS);
                });
    }

    @Configuration
    static class FactoryConfig {
        @Bean
        CasciianTApplicationFactory casciianTApplicationFactory() {
            return (in, out, session) -> mock(TApplication.class);
        }
    }
}
