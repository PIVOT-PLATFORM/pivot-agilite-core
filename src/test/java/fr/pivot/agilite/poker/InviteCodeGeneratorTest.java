package fr.pivot.agilite.poker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InviteCodeGenerator} (US09.1.1).
 */
class InviteCodeGeneratorTest {

    /**
     * Given the generator, when a code is generated, then it is exactly 6 characters long.
     */
    @RepeatedTest(20)
    void generate_returnsSixCharacterCode() {
        assertThat(InviteCodeGenerator.generate()).hasSize(6);
    }

    /**
     * Given the generator, when a code is generated, then every character belongs to the
     * reduced alphabet (excludes ambiguous {@code 0}/{@code O} and {@code 1}/{@code I}).
     */
    @RepeatedTest(20)
    void generate_usesOnlyAllowedAlphabet() {
        String code = InviteCodeGenerator.generate();
        assertThat(code).matches("[" + InviteCodeGenerator.ALPHABET + "]{6}");
        assertThat(code).doesNotContain("0", "1", "I", "O");
    }

    /**
     * Given repeated generation, when generating many codes, then they are not all identical
     * (sanity check that randomness is actually engaged, not a hardcoded value).
     */
    @Test
    void generate_producesVariedCodes() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            codes.add(InviteCodeGenerator.generate());
        }
        assertThat(codes.size()).isGreaterThan(1);
    }
}
