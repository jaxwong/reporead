// Owns: Producer/request checks for annotation and review shapes through actual records and controllers.

package com.reporead.annotation;

import static com.reporead.ContractFixtures.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
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

/** Owns producer/request checks for annotation and review shapes through actual records and controllers. */
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
    void annotationRequestsMatchRealRecordsAndPassControllerValidation() throws Exception {
        var selection = new AnnotationService.Selection(SHA, "b1", 0, 4, "text");
        matches(
                json,
                "requests/create",
                new AnnotationController.CreateRequest(
                        "00000000-0000-0000-0000-000000000001", selection, null, "HIGHLIGHT", null));
        matches(json, "requests/edit", new AnnotationController.EditRequest(null, 1));
        matches(json, "requests/reattach", new AnnotationController.ReattachRequest(1, selection));
        matches(json, "requests/check", new AnnotationController.CheckRequest(1, SHA));
        matches(
                json,
                "requests/review",
                new ReviewController.GradeRequest("00000000-0000-0000-0000-000000000002", 4, AT, SHA));
        var anchor = new Annotations.Passage(SHA, "b1", "text", "", "", 0, 4, List.of());
        var annotation = new Annotations.Annotation(
                9,
                "00000000-0000-0000-0000-000000000001",
                1,
                Annotations.Type.HIGHLIGHT,
                null,
                Annotations.Status.ANCHORED,
                1,
                AT,
                AT,
                anchor,
                new Annotations.Location(anchor, null, 1, 0, 0),
                SHA,
                null,
                null);
        matches(json, "annotations", new AnnotationController.AnnotationList(List.of(annotation)));

        TestEnvironment.reset(db);
        var token = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_WIRE");
        // The requests pass parsing/value validation and reach ownership lookup; no document/GitHub call exists.
        for (String action : List.of("create", "reattach", "check", "review")) {
            String route = switch (action) {
                case "create" -> "/api/documents/999/annotations";
                case "review" -> "/api/annotations/999/reviews";
                default -> "/api/annotations/999/" + action;
            };
            mvc.perform(post(route)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(read("requests/" + action)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        mvc.perform(patch("/api/annotations/999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(read("requests/edit")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
