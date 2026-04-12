package cc.coderm.cascade.config;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AutoConfigurationImportsFileTest {

    private static final String IMPORTS_FILE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    @Test
    void shouldRegisterLimiterAndIdempotentAutoConfigurations() throws Exception {
        InputStream in = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(IMPORTS_FILE);
        assertThat(in).as("AutoConfiguration.imports should exist").isNotNull();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<String> entries = reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();

            assertThat(entries).contains(
                    "cc.coderm.cascade.limiter.config.CascadeLimiterAutoConfiguration",
                    "cc.coderm.cascade.idempotent.config.CascadeIdempotentAutoConfiguration"
            );
        }
    }
}
