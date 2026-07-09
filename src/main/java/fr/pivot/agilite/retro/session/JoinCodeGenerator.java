package fr.pivot.agilite.retro.session;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates globally unique 6-character alphanumeric join codes for retro sessions (US20.1.1).
 *
 * <p><strong>Randomness.</strong> Uses {@link SecureRandom} — never {@code Random}/{@code
 * Math.random()} — matching {@code BoardShareService}'s existing convention in this org.
 * Alphabet is uppercase {@code A-Z} plus digits {@code 0-9} (36 characters); ambiguity between
 * {@code 0}/{@code O} and {@code 1}/{@code I} is deliberately accepted — the code is displayed
 * and copied from the UI, never dictated verbally.
 *
 * <p><strong>Collision handling — design decision.</strong> Checks {@link
 * RetroSessionRepository#existsByJoinCode(String)} before returning a candidate, retrying up to
 * {@value #MAX_ATTEMPTS} times, rather than attempting an insert and catching the {@code
 * DataIntegrityViolationException} from the database {@code UNIQUE} constraint. Chosen for
 * simplicity: the candidate space is 36^6 ≈ 2.18 billion combinations, so a genuine collision
 * during the narrow window between this check and the caller's subsequent {@code save()} is
 * astronomically unlikely in practice. The database {@code UNIQUE} constraint on {@code
 * agilite.retro_sessions.join_code} remains the authoritative correctness guarantee regardless
 * of this approach — it alone is what makes "two sessions never share a join code" actually
 * true, independent of any race in this pre-check.
 *
 * <p>If {@value #MAX_ATTEMPTS} consecutive attempts all collide, {@link #generate()} throws
 * {@link IllegalStateException} — expected to never happen in practice given the size of the
 * candidate space.
 */
@Component
public class JoinCodeGenerator {

    /** Alphabet used to build join codes: uppercase letters and digits (36 characters). */
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /** Fixed length of every generated join code. */
    private static final int CODE_LENGTH = 6;

    /** Maximum number of collision-retry attempts before giving up. */
    private static final int MAX_ATTEMPTS = 5;

    private final RetroSessionRepository sessionRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Constructs the generator with its collision-check dependency.
     *
     * @param sessionRepository repository used to check candidate codes for uniqueness
     */
    public JoinCodeGenerator(final RetroSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * Generates a join code guaranteed not to collide with an existing session's code at the
     * time of the check.
     *
     * @return a 6-character alphanumeric join code
     * @throws IllegalStateException if {@value #MAX_ATTEMPTS} consecutive attempts all collided
     *     with an existing code
     */
    public String generate() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = randomCode();
            if (!sessionRepository.existsByJoinCode(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Unable to generate a unique join code after " + MAX_ATTEMPTS + " attempts");
    }

    /**
     * Draws a single random {@value #CODE_LENGTH}-character candidate from {@link #ALPHABET}.
     *
     * @return a freshly drawn candidate code, not yet checked for uniqueness
     */
    private String randomCode() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            builder.append(ALPHABET.charAt(secureRandom.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
