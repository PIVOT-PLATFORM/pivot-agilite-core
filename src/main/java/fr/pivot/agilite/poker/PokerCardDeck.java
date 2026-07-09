package fr.pivot.agilite.poker;

import java.util.List;

/**
 * Fixed Fibonacci-based card deck for planning poker rooms (US09.1.1).
 *
 * <p>ADR-026 §2 fixes the v1 scope to a single, hardcoded Fibonacci-like sequence — no
 * per-team/per-room customization (T-shirt sizes, custom decks) exists until a dedicated US
 * proves the need (tracked in the E09 README as an unwritten US09.1.3, explicitly deferred to
 * v2+). Every {@link PokerRoom} created in v1 uses exactly this deck; nothing in the API surface
 * lets a caller override it (see {@code CreateRoomRequest}, which has no {@code sequence} field).
 */
public final class PokerCardDeck {

    /** The sequence identifier stored on every room and returned by the API. */
    public static final String SEQUENCE_FIBONACCI = "FIBONACCI";

    /** Fixed card values for the {@link #SEQUENCE_FIBONACCI} deck, in play order. */
    public static final List<String> FIBONACCI_VALUES =
            List.of("0", "1", "2", "3", "5", "8", "13", "21", "34", "55", "89", "?");

    private PokerCardDeck() {
    }
}
