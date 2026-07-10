package fr.pivot.agilite.poker.vote;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity backing a single participant's vote on a planning poker ticket (US09.2.1), table
 * {@code agilite.poker_votes}.
 *
 * <p>Never exposed directly over the API, and never broadcast in full — {@link
 * PokerVoteService#submit} only ever derives an aggregate count from rows of this type; {@code
 * value} itself transits over no channel visible to any participant before US09.2.2's reveal.
 *
 * <p>{@link #participantKey} is a SHA-256 hex digest of the participant's room access token
 * (EN09.1), never the raw token — see {@link PokerVoteService}'s Javadoc for the full security
 * rationale. One row per {@code (ticketId, participantKey)} (unique database constraint):
 * changing a vote before reveal updates {@link #value}/{@link #updatedAt} in place rather than
 * inserting a second row.
 */
@Entity
@Table(name = "poker_votes", schema = "agilite")
public class PokerVote {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "participant_key", nullable = false, length = 64)
    private String participantKey;

    @Column(name = "value", nullable = false, length = 10)
    private String value;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** No-argument constructor required by JPA. */
    protected PokerVote() {
    }

    /**
     * Creates a new vote ready to persist.
     *
     * @param ticketId       the voted-on ticket's identifier
     * @param participantKey the SHA-256 hex digest of the voter's room access token
     * @param value          the chosen card value (one of {@code PokerCardDeck#FIBONACCI_VALUES})
     * @param now            the current instant, used for both {@link #createdAt}/{@link #updatedAt}
     */
    public PokerVote(final UUID ticketId, final String participantKey, final String value, final Instant now) {
        this.ticketId = ticketId;
        this.participantKey = participantKey;
        this.value = value;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** @return database primary key */
    public UUID getId() {
        return id;
    }

    /** @return the voted-on ticket's identifier */
    public UUID getTicketId() {
        return ticketId;
    }

    /** @return the SHA-256 hex digest identifying the voter, never the raw access token */
    public String getParticipantKey() {
        return participantKey;
    }

    /** @return the chosen card value */
    public String getValue() {
        return value;
    }

    /** @return the first submission timestamp */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return the last update timestamp (bumped on every vote change) */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Updates this vote's value (change of vote before reveal), bumping {@link #updatedAt}.
     *
     * @param value the newly chosen card value
     * @param now   the current instant
     */
    public void changeValue(final String value, final Instant now) {
        this.value = value;
        this.updatedAt = now;
    }
}
