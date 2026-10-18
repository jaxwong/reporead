// Owns: Producer checks for the repository-refresh acknowledgement shape.

package com.reporead.sync;

import static com.reporead.ContractFixtures.*;

import com.reporead.SharedSpringTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/** Owns producer checks for the repository-refresh acknowledgement shape. */
class WireContractTest extends SharedSpringTest {
    @Autowired
    JsonMapper json;

    @Test
    void theSyncResultNamesItsRepository() throws Exception {
        matches(json, "synced", new RepositorySync.Result(7, COMMIT, 1, AT));
    }
}
