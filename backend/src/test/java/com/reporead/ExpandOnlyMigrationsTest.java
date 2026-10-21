package com.reporead;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Migrations from V15 on leave a schema the previous release still runs on (ADR-24), so going back after a deploy is
 * putting the previous jar back. A step that would break it belongs in a later release, once no deployed release uses
 * what it removes, and its migration says so in a {@code -- Contract:} line. V1 to V14 were written before the rule.
 */
class ExpandOnlyMigrationsTest {
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final int FIRST_CHECKED = 15;
    private static final Pattern VERSION = Pattern.compile("V(\\d+)__.+\\.sql");

    /** Steps the previous release cannot run after: it reads, writes or leaves null what they remove or require. */
    private static final List<Pattern> CONTRACT_STEPS = Stream.of(
                    "\\bdrop table\\b",
                    "\\bdrop column\\b",
                    "\\brename\\b",
                    "\\balter (column )?\\S+ (set data )?type\\b",
                    "\\bset not null\\b",
                    "\\badd column\\b(?!.*\\bdefault\\b).*\\bnot null\\b")
            .map(Pattern::compile)
            .toList();

    /** The clauses of {@code sql} that would break the previous release, comments and parenthesized parts removed. */
    static List<String> contractSteps(String sql) {
        String text = sql.replaceAll("--[^\n]*", " ")
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        // A column list or a type like numeric(10, 2) holds commas that do not separate clauses.
        for (String before = ""; !before.equals(text); ) {
            before = text;
            text = text.replaceAll("\\([^()]*\\)", "()");
        }
        var steps = new ArrayList<String>();
        for (String clause : text.split("[;,]")) {
            String step = clause.strip();
            if (CONTRACT_STEPS.stream()
                    .anyMatch(pattern -> pattern.matcher(step).find())) steps.add(step);
        }
        return steps;
    }

    @Test
    void stepsThatBreakThePreviousReleaseAreRecognized() {
        for (String step : List.of(
                "DROP TABLE t",
                "DROP TABLE IF EXISTS t",
                "ALTER TABLE t DROP COLUMN c",
                "ALTER TABLE t DROP COLUMN IF EXISTS c",
                "ALTER TABLE t RENAME COLUMN c TO d",
                "ALTER TABLE t RENAME TO u",
                "ALTER TABLE t ALTER COLUMN c TYPE bigint",
                "ALTER TABLE t ALTER c SET DATA TYPE text USING c::text",
                "ALTER TABLE t ALTER COLUMN c SET NOT NULL",
                "ALTER TABLE t ADD COLUMN c text NOT NULL",
                "ALTER TABLE t ADD COLUMN c numeric(10, 2) NOT NULL CHECK (c > 0)")) {
            assertEquals(1, contractSteps(step + ";").size(), step);
        }
        for (String step : List.of(
                "CREATE TABLE t (id bigint PRIMARY KEY, name text NOT NULL, price numeric(10, 2) NOT NULL)",
                "ALTER TABLE t ADD COLUMN c text",
                "ALTER TABLE t ADD COLUMN c text NOT NULL DEFAULT ''",
                "ALTER TABLE t ADD COLUMN c int, ADD COLUMN d int NOT NULL DEFAULT 0",
                "CREATE INDEX t_c ON t (c)",
                "ALTER TABLE t DROP CONSTRAINT t_check",
                "ALTER TABLE t ALTER COLUMN c DROP NOT NULL",
                "-- drop table t is only a comment here")) {
            assertEquals(List.of(), contractSteps(step + ";"), step);
        }
    }

    @Test
    void migrationsSinceTheRuleKeepThePreviousReleaseWorking() throws IOException {
        var unmarked = new ArrayList<String>();
        try (var files = Files.list(MIGRATIONS)) {
            for (Path file : files.toList()) {
                Matcher name = VERSION.matcher(file.getFileName().toString());
                assertTrue(name.matches(), "Not a Flyway migration name: " + file);
                if (Integer.parseInt(name.group(1)) < FIRST_CHECKED) continue;
                String sql = Files.readString(file);
                var steps = contractSteps(sql);
                if (!steps.isEmpty() && !sql.contains("\n-- Contract: ") && !sql.startsWith("-- Contract: "))
                    unmarked.add(file.getFileName() + ": " + steps);
            }
        }
        assertEquals(
                List.of(),
                unmarked,
                "These steps break the previous release. Move each to a release after the one that stops using what it"
                        + " removes, and say so in a '-- Contract:' line (ADR-24).");
    }
}
