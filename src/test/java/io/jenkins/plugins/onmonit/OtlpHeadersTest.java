package io.jenkins.plugins.onmonit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.Map;
import org.junit.jupiter.api.Test;

class OtlpHeadersTest {

    @Test
    void nullAndBlankGiveEmpty() {
        assertThat(OtlpHeaders.parse(null)).isEmpty();
        assertThat(OtlpHeaders.parse("  ")).isEmpty();
    }

    @Test
    void singlePair() {
        assertThat(OtlpHeaders.parse("Authorization=Bearer test-token"))
                .containsExactly(entry("Authorization", "Bearer test-token"));
    }

    @Test
    void customKey() {
        assertThat(OtlpHeaders.parse("X-Api-Key=abc")).containsExactly(entry("X-Api-Key", "abc"));
    }

    @Test
    void multiplePairsKeepOrder() {
        assertThat(OtlpHeaders.parse("a=b,Authorization=Bearer x"))
                .containsExactly(entry("a", "b"), entry("Authorization", "Bearer x"));
    }

    @Test
    void trimsKeysAndValues() {
        assertThat(OtlpHeaders.parse(" a = b , c=d ")).containsExactly(entry("a", "b"), entry("c", "d"));
    }

    @Test
    void percentDecodesValue() {
        assertThat(OtlpHeaders.parse("Authorization=Basic%20abc")).containsExactly(entry("Authorization", "Basic abc"));
        assertThat(OtlpHeaders.parse("k=caf%C3%A9")).containsExactly(entry("k", "café"));
        assertThat(OtlpHeaders.parse("k=a%2Cb%3Dc")).containsExactly(entry("k", "a,b=c"));
    }

    @Test
    void plusAndEqualsStayLiteral() {
        assertThat(OtlpHeaders.parse("Authorization=Basic YWJj+ZA=="))
                .containsExactly(entry("Authorization", "Basic YWJj+ZA=="));
    }

    @Test
    void skipsMalformedMembers() {
        assertThat(OtlpHeaders.parse("novalue,=x,,a=b,")).containsExactly(entry("a", "b"));
    }

    @Test
    void malformedPercentKeepsRawValue() {
        assertThat(OtlpHeaders.parse("k=100%")).containsExactly(entry("k", "100%"));
    }

    @Test
    void controlCharactersDropPair() {
        assertThat(OtlpHeaders.parse("k=a%0Ab,c=d")).containsExactly(entry("c", "d"));
    }

    @Test
    void duplicateKeyLastWins() {
        assertThat(OtlpHeaders.parse("k=1,k=2")).containsExactly(entry("k", "2"));
    }

    @Test
    void emptyMapGivesEmptyBlock() {
        assertThat(OtlpHeaders.toYamlBlock(Map.of())).isEmpty();
    }

    @Test
    void yamlBlockOneLinePerHeader() {
        assertThat(OtlpHeaders.toYamlBlock(OtlpHeaders.parse("a=b,X-Api-Key=abc")))
                .isEqualTo("headers:\n      'a': 'b'\n      'X-Api-Key': 'abc'");
    }

    @Test
    void yamlQuotesAreDoubled() {
        assertThat(OtlpHeaders.toYamlBlock(Map.of("k", "it's \"x\" & y: z")))
                .isEqualTo("headers:\n      'k': 'it''s \"x\" & y: z'");
    }
}
