package fr.pivot.agilite.retro.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JoinCodeGenerator}, deterministically forcing the collision-retry path
 * via a mocked {@link RetroSessionRepository} — real contention against a live database is
 * covered separately by {@code RetroSessionConcurrencyIT} (US20.1.1).
 */
@ExtendWith(MockitoExtension.class)
class JoinCodeGeneratorTest {

    @Mock
    private RetroSessionRepository sessionRepository;

    private JoinCodeGenerator generator;

    /** Initialises the generator under test with a mocked repository. */
    @BeforeEach
    void setUp() {
        generator = new JoinCodeGenerator(sessionRepository);
    }

    /**
     * Given no existing code ever collides, when generate() is called,
     * then it returns a 6-character alphanumeric code drawn from the expected alphabet.
     */
    @RepeatedTest(20)
    void generate_whenNoCollision_returnsSixCharacterAlphanumericCode() {
        when(sessionRepository.existsByJoinCode(anyString())).thenReturn(false);

        String code = generator.generate();

        assertThat(code).hasSize(6);
        assertThat(code).matches("[A-Z0-9]{6}");
    }

    /**
     * Given the first two candidates collide and the third does not, when generate() is
     * called, then it retries and eventually returns without throwing — proving the bounded
     * regeneration-on-collision mechanism actually works, not just the happy path.
     */
    @Test
    void generate_whenFirstTwoAttemptsCollide_retriesAndSucceeds() {
        when(sessionRepository.existsByJoinCode(anyString()))
                .thenReturn(true, true, false);

        String code = generator.generate();

        assertThat(code).hasSize(6);
        verify(sessionRepository, times(3)).existsByJoinCode(anyString());
    }

    /**
     * Given every one of the 5 allowed attempts collides, when generate() is called,
     * then it throws {@link IllegalStateException} rather than looping forever or silently
     * returning a colliding code.
     */
    @Test
    void generate_whenAllAttemptsCollide_throwsIllegalStateException() {
        when(sessionRepository.existsByJoinCode(anyString())).thenReturn(true);

        assertThatThrownBy(() -> generator.generate())
                .isInstanceOf(IllegalStateException.class);

        verify(sessionRepository, times(5)).existsByJoinCode(anyString());
    }
}
