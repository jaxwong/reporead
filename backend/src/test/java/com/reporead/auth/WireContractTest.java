package com.reporead.auth;

import static com.reporead.ContractFixtures.*;

import com.reporead.SharedSpringTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

class WireContractTest extends SharedSpringTest {
    @Autowired
    JsonMapper json;

    @Test
    void theSessionAndExplicitNullOwnerMatchTheSharedExamples() throws Exception {
        matches(
                json,
                "session",
                new AppSessions.IssuedSession("TEST_ONLY_SESSION", AT, new AppUser(1, 42, "test-only")));
        matches(
                json,
                "requests/token",
                new AppAuthController.TokenRequest("TEST_ONLY_CODE", "TEST_ONLY_VERIFIER", null));
    }
}
