package fr.pivot.agilite.poker;

import java.security.SecureRandom;

/**
 * Generates 6-character invite codes for planning poker rooms (US09.1.1).
 *
 * <p>Alphabet deliberately excludes visually ambiguous characters ({@code 0}/{@code O},
 * {@code 1}/{@code I}) — a facilitator typically reads this code aloud or copies it from a
 * screen share, and typos on ambiguous glyphs are a common real-world source of "invalid code"
 * support requests for this class of product. 32 characters ^ 6 positions ≈ 1.07 billion
 * combinations — ample collision headroom for the uniqueness retry in {@link PokerRoomService}.
 */
public final class InviteCodeGenerator {

    /** Alphabet excluding {@code 0}/{@code O} and {@code 1}/{@code I} to avoid ambiguous typos. */
    static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    private static final int CODE_LENGTH = 6;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private InviteCodeGenerator() {
    }

    /**
     * Generates a random 6-character invite code from {@link #ALPHABET}.
     *
     * @return a freshly generated invite code (not guaranteed unique — the caller must check)
     */
    public static String generate() {
        final StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            builder.append(ALPHABET.charAt(SECURE_RANDOM.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
