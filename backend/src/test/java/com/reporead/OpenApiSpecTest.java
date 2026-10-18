// Owns: Deriving openapi.json, the one source of the app's wire models, and checking it against actual serialization.

package com.reporead;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.jackson.TypeNameResolver;
import io.swagger.v3.core.util.Json;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springdoc.core.customizers.ServerBaseUrlCustomizer;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Derives the published API from the application's controllers and records and fails when the committed
 * {@code backend/openapi.json} is not exactly that, rewriting it: a backend change reaches the app's generated wire
 * models only through the rewritten, committed file. springdoc reads the records with swagger-core; these rules add
 * what the Java types do not say:
 *
 * <ul>
 *   <li>a nested type's schema is named with its enclosing types ({@code Library.Snapshot} is {@code LibrarySnapshot}),
 *       and no two types can publish one name;
 *   <li>every property is required, because the application mapper writes every record component, nulls included;
 *   <li>a property is nullable exactly when its record component's type is JSpecify {@code @Nullable}.
 * </ul>
 */
@SpringBootTest(
        properties = {
            "springdoc.paths-to-match=/api/**",
            "springdoc.api-docs.version=openapi_3_0",
            "springdoc.default-produces-media-type=application/json",
            "springdoc.writer-with-order-by-keys=true",
            "springdoc.writer-with-default-pretty-printer=true"
        })
class OpenApiSpecTest {
    /** Relative to the backend project, the test task's working directory. */
    private static final Path COMMITTED = Path.of("openapi.json");

    /** Each published schema name and the one type it describes, filled while springdoc resolves the API. */
    private static final Map<String, Class<?>> PUBLISHED = new ConcurrentHashMap<>();

    @Autowired
    OpenApiWebMvcResource springdoc;

    @Autowired
    JsonMapper json;

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry properties) {
        TestEnvironment.register(properties);
    }

    @TestConfiguration
    static class Rules {
        @Bean
        ModelResolver ownerNamedSchemas() {
            return new ModelResolver(Json.mapper(), new OwnerQualifiedNames());
        }

        @Bean
        PropertyCustomizer nullOnlyWhereDeclared() {
            return (property, type) -> {
                if (type.getParent() != null
                        && declaredNullable(type.getParent().getName(), type.getPropertyName())) {
                    property.setNullable(true);
                }
                return property;
            };
        }

        @Bean
        OpenApiCustomizer everyPropertyRequired() {
            return api -> api.getComponents().getSchemas().values().forEach(schema -> {
                if (schema.getProperties() != null) {
                    schema.setRequired(new ArrayList<>(schema.getProperties().keySet()));
                }
            });
        }

        /** Debug builds and the hosted app reach different hosts, so the spec names none. */
        @Bean
        ServerBaseUrlCustomizer noServer() {
            return (url, request) -> null;
        }
    }

    /** Names a nested type with its enclosing types and refuses a second type under a name already published. */
    static final class OwnerQualifiedNames extends TypeNameResolver {
        @Override
        protected String getNameOfClass(Class<?> type) {
            String name = type.getSimpleName();
            for (var owner = type.getEnclosingClass(); owner != null; owner = owner.getEnclosingClass()) {
                name = owner.getSimpleName() + name;
            }
            Class<?> earlier = PUBLISHED.putIfAbsent(name, type);
            if (earlier != null && earlier != type) {
                throw new IllegalStateException("Schema " + name + " would describe both " + earlier + " and " + type);
            }
            return name;
        }
    }

    /**
     * Whether a published property's accessor is {@code @Nullable}: a record component's accessor carries the
     * component's type annotations, and a computed property such as {@code fullName} is an accessor of its own.
     */
    private static boolean declaredNullable(String schema, String property) {
        Class<?> owner = PUBLISHED.get(schema);
        if (owner == null) throw new IllegalStateException("Property " + property + " of unpublished schema " + schema);
        try {
            return owner.getDeclaredMethod(property).getAnnotatedReturnType().isAnnotationPresent(Nullable.class);
        } catch (NoSuchMethodException error) {
            throw new IllegalStateException(
                    "Schema " + schema + " publishes " + property + ", but " + owner + " has no accessor of that name",
                    error);
        }
    }

    private byte[] derived() throws IOException {
        var spec = new String(springdoc.openapiJson(new MockHttpServletRequest(), "/v3/api-docs", Locale.ROOT), UTF_8);
        return (spec + "\n").getBytes(UTF_8);
    }

    @Test
    void theCommittedSpecIsWhatTheBackendPublishes() throws IOException {
        byte[] derived = derived();
        if (!Arrays.equals(derived, Files.readAllBytes(COMMITTED))) {
            Files.write(COMMITTED, derived);
            fail(COMMITTED.toAbsolutePath() + " was not what the controllers and records publish; it now is. Review "
                    + "the change and commit it with the code that caused it.");
        }
    }

    /**
     * swagger-core reads the records with its own Jackson; clients are answered by the application mapper. A test-only
     * sample of every published record, once with null in each {@code @Nullable} component and once with values, must
     * serialize to its schema: the same keys, every one present, the published JSON types and enum values, and null
     * only where nullable. This is where {@code @JsonUnwrapped} and {@code @JsonValue} must agree too.
     */
    @Test
    void everyPublishedRecordSerializesAsItsSchema() throws Exception {
        var schemas = json.readTree(derived()).path("components").path("schemas");
        for (var entry : schemas.properties()) {
            Class<?> type = PUBLISHED.get(entry.getKey());
            assertNotNull(type, "No type published schema " + entry.getKey());
            for (boolean nulls : List.of(true, false)) {
                conforms(entry.getKey(), json.valueToTree(sample(type, nulls)), entry.getValue(), schemas, nulls);
            }
        }
    }

    /** With {@code nulls}, every {@code @Nullable} component is null, so a nullable property holding a value is not. */
    private static void conforms(String path, JsonNode value, JsonNode schema, JsonNode schemas, boolean nulls) {
        if (schema.has("$ref")) {
            var name = schema.get("$ref").stringValue().substring("#/components/schemas/".length());
            conforms(path, value, schemas.path(name), schemas, nulls);
            return;
        }
        boolean nullable = schema.path("nullable").asBoolean(false);
        assertEquals(nulls && nullable, value.isNull(), path + (nullable ? " is nullable" : " is not nullable"));
        if (value.isNull()) return;
        switch (schema.path("type").asString()) {
            case "object" -> {
                var properties = schema.path("properties");
                assertEquals(names(properties), names(value), path + " keys");
                for (var property : properties.properties()) {
                    var name = property.getKey();
                    conforms(path + "." + name, value.get(name), property.getValue(), schemas, nulls);
                }
            }
            case "array" -> {
                assertTrue(value.isArray(), path + " is not an array");
                for (var item : value) conforms(path + "[]", item, schema.path("items"), schemas, nulls);
            }
            case "string" -> {
                assertTrue(value.isString(), path + " is not a string");
                if (schema.path("format").asString().equals("date-time")) Instant.parse(value.stringValue());
                if (schema.has("enum")) assertTrue(names(schema.path("enum")).contains(value.stringValue()), path);
            }
            case "integer" -> assertTrue(value.isIntegralNumber(), path + " is not an integer");
            case "boolean" -> assertTrue(value.isBoolean(), path + " is not a boolean");
            default -> fail(path + " has a schema type this check does not cover: " + schema);
        }
    }

    private static Set<String> names(JsonNode node) {
        var names = new HashSet<String>();
        if (node.isArray()) node.forEach(item -> names.add(item.stringValue()));
        else names.addAll(node.propertyNames());
        return names;
    }

    /**
     * A test-only value of a published type; a {@code @Nullable} component is null when {@code nulls}, else a value.
     */
    private static Object sample(Type type, boolean nulls) throws ReflectiveOperationException {
        if (type instanceof ParameterizedType list && list.getRawType() == List.class) {
            return List.of(sample(list.getActualTypeArguments()[0], nulls));
        }
        var raw = (Class<?>) type;
        if (raw == long.class || raw == Long.class) return 1L;
        if (raw == int.class || raw == Integer.class) return 1;
        if (raw == boolean.class) return true;
        if (raw == double.class) return 0.5;
        if (raw == String.class) return "test-only";
        if (raw == Instant.class) return Instant.EPOCH;
        if (raw.isEnum()) return raw.getEnumConstants()[0];
        if (!raw.isRecord()) throw new IllegalStateException("No test-only sample of " + type + "; expected a record");
        var components = raw.getRecordComponents();
        var values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            boolean nullable = components[i].getAnnotatedType().isAnnotationPresent(Nullable.class);
            values[i] = nulls && nullable ? null : sample(components[i].getGenericType(), nulls);
        }
        var constructor = raw.getDeclaredConstructor(
                Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
        constructor.setAccessible(true);
        return constructor.newInstance(values);
    }
}
