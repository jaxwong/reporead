package com.reporead;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Every reporead.* setting is required: a missing environment variable fails startup and names the setting. */
class RepoReadPropertiesTest {
    private static final Map<String, Object> COMPLETE = Map.of(
        "reporead.public-url", "https://reporead.test",
        "reporead.allowed-github-user-ids", "42",
        "reporead.github.client-id", "test-only-not-a-github-app",
        "reporead.github.client-secret-file", "/test-only/client-secret",
        "reporead.github.token-key-file", "/test-only/token-key");

    private static RepoReadProperties bind(Map<String, Object> values) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-only", values));
        return Binder.get(environment).bindOrCreate("reporead", RepoReadProperties.class);
    }

    @Test void bindsEverySetting() {
        var properties = bind(COMPLETE);
        assertEquals("https://reporead.test", properties.publicUrl());
        assertEquals("42", properties.allowedGithubUserIds());
        assertEquals("test-only-not-a-github-app", properties.github().clientId());
        assertEquals("/test-only/token-key", properties.github().tokenKeyFile().toString());
    }

    @Test void anUnsetEnvironmentVariableIsRefusedByName() {
        for (String name : COMPLETE.keySet()) {
            // application.properties reads each setting from an environment variable; the binder keeps an unset one as text.
            var values = new HashMap<>(COMPLETE);
            values.put(name, "${TEST_ONLY_UNSET_VARIABLE}");
            var error = assertThrows(BindException.class, () -> bind(values), name);
            assertTrue(rootMessage(error).contains(name), () -> name + ": " + rootMessage(error));
        }
    }

    @Test void aMissingOrBlankSettingIsRefusedByName() {
        for (String name : COMPLETE.keySet()) {
            for (String value : new String[] {null, " "}) {
                var values = new HashMap<>(COMPLETE);
                if (value == null) values.remove(name); else values.put(name, value);
                var error = assertThrows(RuntimeException.class, () -> bind(values), name);
                assertTrue(rootMessage(error).contains(name), () -> name + "=" + value + ": " + rootMessage(error));
            }
        }
    }

    private static String rootMessage(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage();
    }
}
