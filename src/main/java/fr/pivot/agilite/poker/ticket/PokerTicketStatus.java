package fr.pivot.agilite.poker.ticket;

/**
 * Lifecycle status of a {@link PokerTicket} (US09.2.1).
 *
 * <p>A room has at most one {@link #VOTING} ticket at a time (enforced by a partial unique
 * database index — see {@code V1__schema_init.sql}, same precedent as {@code
 * agilite.wheel_entry}'s partial unique indexes, US14.1.1). Transition to {@link #REVEALED} is
 * US09.2.2's exclusive responsibility — this US never writes that value, but already checks
 * against it defensively when a vote is submitted (a revealed ticket no longer accepts votes).
 */
public enum PokerTicketStatus {

    /** Open for voting — the only status a ticket has when created by this US. */
    VOTING,

    /** Revealed — written exclusively by US09.2.2, never by this US. */
    REVEALED
}
