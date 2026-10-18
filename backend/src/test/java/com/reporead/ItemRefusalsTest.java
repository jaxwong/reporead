// Owns: Checking the shared item-refusal examples against the status the server sends with each code.

package com.reporead;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code api-contract/item-refusals.json} lists the failures the app may treat as the server refusing an item for good
 * (its RefusalTest); each status/code pair there must be the one this server actually sends.
 */
class ItemRefusalsTest {
    @Test
    void eachExampleUsesTheServersActualStatusForItsCode() throws IOException {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/api-contract/item-refusals.json"))) {
            for (var item : JsonMapper.builder().build().readTree(input)) {
                var code = ApiFailure.Code.valueOf(item.get("code").stringValue());
                assertEquals(code.status, item.get("status").intValue(), code.name());
            }
        }
    }
}
