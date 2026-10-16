package com.reporead.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.reporead.SharedSpringTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The release app's verified sign-in link: the statement Android checks, and the page for a browser that keeps it. */
class AppLinkTest extends SharedSpringTest {
    @Autowired
    MockMvc mvc;

    @Test
    void theStatementLetsOnlyTheReleaseAppSignedWithItsKeyOpenTheLink() throws Exception {
        mvc.perform(get("/.well-known/assetlinks.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].relation[0]").value("delegate_permission/common.handle_all_urls"))
                .andExpect(jsonPath("$[0].target.namespace").value("android_app"))
                .andExpect(jsonPath("$[0].target.package_name").value("com.reporead.android"))
                .andExpect(jsonPath("$[0].target.sha256_cert_fingerprints.length()")
                        .value(1))
                .andExpect(jsonPath("$[0].target.sha256_cert_fingerprints[0]")
                        .value(matchesPattern("([0-9A-F]{2}:){31}[0-9A-F]{2}")));
    }

    @Test
    void aBrowserThatKeepsTheLinkCanOpenOnlyTheReleaseApp() throws Exception {
        String code = "TEST_ONLY_".repeat(4) + "abc";
        var page = mvc.perform(get("/app/signed-in").param("code", code))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(content()
                        .string(containsString("href=\"intent://127.0.0.1/app/signed-in?code=" + code
                                + "#Intent;scheme=https;package=com.reporead.android;end\"")))
                .andReturn();
        assertNull(page.getRequest().getSession(false));
        for (String bad : new String[] {"<a href=x>", "too-short", ""}) {
            mvc.perform(get("/app/signed-in").param("code", bad)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/app/signed-in")).andExpect(status().isBadRequest());
    }
}
