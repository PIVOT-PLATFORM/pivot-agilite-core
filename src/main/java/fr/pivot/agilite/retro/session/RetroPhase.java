package fr.pivot.agilite.retro.session;

/**
 * Lifecycle phase of a retrospective session (US20.1.1, animated in real time by US20.1.2a/b/c).
 *
 * <p>A session is created in {@link #CONTRIBUTION} and transitions forward through the other
 * phases; the real-time transition logic itself (timers, facilitator-triggered advance) is
 * US20.1.2a/b/c's scope — this US only defines the enum and the initial value.
 */
public enum RetroPhase {

    /** Participants submit cards into format-defined columns (US20.1.2a). */
    CONTRIBUTION,

    /** Cards are revealed to all participants (US20.1.2a). */
    REVUE,

    /** Participants dot-vote on revealed cards (US20.1.2b). */
    VOTE,

    /** Team turns top-voted cards into actions (US20.3.1). */
    ACTION,

    /** Session is closed — read-only from then on (US20.1.2c). */
    CLOSED
}
