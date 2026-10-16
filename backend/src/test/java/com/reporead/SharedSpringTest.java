package com.reporead;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The Spring context most tests share: the whole application on MockMvc against the shared PostgreSQL container
 * (TestEnvironment). Spring caches one context per distinct configuration, and a @DynamicPropertySource method declared
 * in each class makes each configuration distinct, so these classes extend this one instead. Tests that change
 * properties or need a real server declare their own.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class SharedSpringTest {
    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry properties) {
        TestEnvironment.register(properties);
    }
}
