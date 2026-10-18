// Owns: Shared test-only wire constants, resource reads and structural producer/example comparison.

package com.reporead;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** Shared test-only wire examples; actual backend records, never Android's encoder, verify their shape. */
public final class ContractFixtures {
    public static final String SHA = "a".repeat(40);
    public static final String COMMIT = "b".repeat(40);
    public static final java.time.Instant AT = java.time.Instant.parse("2000-01-01T00:00:00Z");

    private ContractFixtures() {}

    public static String read(String name) throws IOException {
        try (var input = Objects.requireNonNull(
                ContractFixtures.class.getResourceAsStream("/api-contract/" + name + ".json"), name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static void matches(JsonMapper json, String name, Object record) throws IOException {
        assertEquals(json.readTree(read(name)), json.readTree(json.writeValueAsBytes(record)), name);
    }
}
