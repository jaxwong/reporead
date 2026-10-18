// Owns: Producer checks for stored-data counts and confirmed disconnect/account-deletion shapes.

package com.reporead.datacontrol;

import static com.reporead.ContractFixtures.*;

import com.reporead.SharedSpringTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/** Owns producer checks for stored-data counts and confirmed disconnect/account-deletion shapes. */
class WireContractTest extends SharedSpringTest {
    @Autowired
    JsonMapper json;

    @Test
    void deletionAcknowledgementsAreJsonRecordsNotNoContent() throws Exception {
        matches(json, "stored-data", new ConnectionData.Counts(1, 1, 1, 1, 1));
        matches(json, "disconnected", new ConnectionData.Disconnected(List.of(1L), 1, 1, 1, 1));
        matches(json, "account-deleted", new AccountDeletion.Deleted(1, 1, 1, 1, 1));
    }
}
