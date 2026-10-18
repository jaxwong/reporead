package com.reporead;

import static com.reporead.ContractFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

class WireContractTest extends SharedSpringTest {
    @Autowired
    JsonMapper json;

    @Test
    void failureCodeAndMessageAreText() throws Exception {
        matches(json, "failure", new ApiErrors.Body(ApiFailure.Code.NOT_FOUND, "Test-only missing item"));
    }

    @Test
    void itemRefusalExamplesUseTheServersActualStatusForEachCode() throws Exception {
        for (var item : json.readTree(read("item-refusals"))) {
            var code = ApiFailure.Code.valueOf(item.get("code").stringValue());
            assertEquals(code.status, item.get("status").intValue(), code.name());
        }
    }
}
