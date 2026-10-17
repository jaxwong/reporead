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
                "drop table t",
                "drop table if exists t",
                "alter table t drop column c",
                "alter table t drop column if exists c",
                "alter table t rename column c to d",
                "alter table t rename to u",
                "alter table t alter column c type bigint",
                "alter table t alter c set data type text using c::text",
                "alter table t alter column c set not null",
                "alter table t add column c text not null",
                "alter table t add column c numeric(10, 2) not null check (c > 0)")) {
            assertEquals(1, contractSteps(step + ";").size(), step);
        }
        for (String step : List.of(
                "create table t (id bigint primary key, name text not null, price numeric(10, 2) not null)",
                "alter table t add column c text",
                "alter table t add column c text not null default ''",
                "alter table t add column c int, add column d int not null default 0",
                "create index t_c on t (c)",
                "alter table t drop constraint t_check",
                "alter table t alter column c drop not null",
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
