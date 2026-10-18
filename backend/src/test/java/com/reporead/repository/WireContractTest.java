// Owns: Producer checks for available repositories and connection-request examples.

package com.reporead.repository;

import static com.reporead.ContractFixtures.*;

import com.reporead.SharedSpringTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/** Owns producer checks for available repositories and connection-request examples. */
class WireContractTest extends SharedSpringTest {
    @Autowired
    JsonMapper json;

    @Test
    void availableAndConnectMatchTheSharedExamples() throws Exception {
        matches(
                json,
                "available",
                new RepositoryController.AvailableList(
                        List.of(new RepositoryConnections.Available(11, 7, "test-only/notes", true, "main", null))));
        matches(json, "requests/connect", new RepositoryController.ConnectRequest(7L));
    }
}
