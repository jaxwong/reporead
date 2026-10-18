package com.reporead.reading;

import static com.reporead.ContractFixtures.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.reporead.SharedSpringTest;
import com.reporead.TestEnvironment;
import com.reporead.auth.AppSessions;
import com.reporead.auth.TestSessions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

class WireContractTest extends SharedSpringTest {
    @Autowired
    JsonMapper json;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient db;

    @Autowired
    AppSessions sessions;

    @Autowired
    OAuth2AuthorizedClientService clients;

    @Autowired
    ClientRegistrationRepository registrations;

    @Test
    void theReadingRequestHasOneTypedAnchorIncludingItsNullablePrefix() throws Exception {
        matches(
                json,
                "requests/reading",
                new ReadingController.SaveRequest(SHA, 50, new ReadingStates.Anchor(List.of(), null, 0), AT));
        TestEnvironment.reset(db);
        var token = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_WIRE");
        mvc.perform(put("/api/documents/999/reading-state")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(read("requests/reading")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
