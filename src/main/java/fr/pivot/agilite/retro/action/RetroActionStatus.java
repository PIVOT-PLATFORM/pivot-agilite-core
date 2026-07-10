package fr.pivot.agilite.retro.action;

/**
 * Lifecycle status of a retrospective action (US20.3.1).
 *
 * <p>Unlike {@link fr.pivot.agilite.retro.session.RetroPhase}, there is deliberately no strict
 * state machine between these four values — {@code RetroActionService#updateStatus} allows any
 * transition, including reopening an {@link #ABANDONNEE} action, exactly as this US's AC
 * specifies ("transitions libres entre les 4 statuts").
 */
public enum RetroActionStatus {

    /** Newly created action, not yet started — the default status on creation. */
    A_FAIRE,

    /** Action currently being worked on. */
    EN_COURS,

    /** Action completed. */
    TERMINEE,

    /** Action abandoned — may still be reopened to any other status. */
    ABANDONNEE
}
