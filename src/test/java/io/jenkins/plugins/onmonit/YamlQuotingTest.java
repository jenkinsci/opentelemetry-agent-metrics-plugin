package io.jenkins.plugins.onmonit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class YamlQuotingTest {

    private final YamlQuoting yaml = new YamlQuoting();

    /** Parses {@code k: <quoted>} and returns the value, undoing the collector's {@code $$} escape. */
    private static String roundTrip(String quoted) {
        Map<String, Object> m = new Yaml().load("k: " + quoted + "\n");
        return String.valueOf(m.get("k")).replace("$$", "$");
    }

    @Test
    void nullIsEmptyString() {
        assertThat(yaml.quote(null)).isEqualTo("\"\"");
    }

    @Test
    void plainValueIsDoubleQuoted() {
        assertThat(yaml.quote("abc")).isEqualTo("\"abc\"");
    }

    @Test
    void nonStringIsQuotedViaToString() {
        assertThat(yaml.quote(9100)).isEqualTo("\"9100\"");
    }

    @Test
    void dollarIsDoubledForCollectorExpansion() {
        assertThat(yaml.quote("a$b${env:X}")).isEqualTo("\"a$$b$${env:X}\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "it's \"fine\"",
        "a & b",
        "back\\slash",
        "line1\nline2\r\n\ttab",
        "nul\u0000bell\u0007del\u007fnel\u0085",
        "ls ps bom﻿",
        "unicode äöü 日本 😀",
        "# not a comment",
        "key: value",
        "- [list] {map} *alias &anchor !tag |",
        "  leading and trailing  ",
        "${env:HOME} $HOME $$",
        "",
    })
    void valuesRoundTripThroughYaml(String value) {
        assertThat(roundTrip(yaml.quote(value))).isEqualTo(value);
    }

    @Test
    void outputIsSingleLine() {
        assertThat(yaml.quote("a\nb c\u0085d")).doesNotContain("\n", " ", "\u0085");
    }
}
