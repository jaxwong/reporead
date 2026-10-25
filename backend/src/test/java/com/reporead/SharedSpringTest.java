package com.reporead;

import com.reporead.auth.AppSessions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The Spring context most tests share: the whole application on MockMvc against the shared PostgreSQL container
 * (TestEnvironment). Spring caches one context per distinct configuration, and a @DynamicPropertySource method declared
 * in each class makes each configuration distinct, so these classes extend this one instead. Tests that change
 * properties or need a real server declare their own.
 *
 * <p>The beans almost every subclass uses are declared here once: the routes (mvc), the database (db), and the three
 * that TestSessions signs a test user in with.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class SharedSpringTest {
    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcClient db;

    @Autowired
    protected AppSessions sessions;

    @Autowired
    protected OAuth2AuthorizedClientService clients;

    @Autowired
    protected ClientRegistrationRepository registrations;

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry properties) {
        TestEnvironment.register(properties);
    }
}
