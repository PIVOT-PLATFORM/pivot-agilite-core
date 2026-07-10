package fr.pivot.agilite.poker.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity backing a planning poker estimation ticket (US09.2.1), table {@code
 * agilite.poker_tickets}.
 *
 * <p>Never exposed directly over the API — {@code PokerTicketController} always returns a {@code
 * TicketResponse} DTO built by {@link PokerTicketService}, per this repo's "no JPA entity in API
 * responses" standard. {@code UUID} primary key, matching {@code fr.pivot.agilite.poker.PokerRoom}
 * and every other room-scoped resource in this module.
 *
 * <p>A room has at most one ticket with {@link PokerTicketStatus#VOTING} at a time — enforced
 * both here (see {@link PokerTicketService#create}) and by a partial unique database index (the
 * stronger, DB-level guarantee), same "structural guarantee over app-only discipline" convention
 * already used by {@code agilite.retro_cards}' anonymity {@code CHECK} constraint (US20.1.1).
 */
@Entity
@Table(name = "poker_tickets", schema = "agilite")
public class PokerTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PokerTicketStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revealed_at")
    private Instant revealedAt;

    /** No-argument constructor required by JPA. */
    protected PokerTicket() {
    }

    /**
     * Creates a new ticket ready to persist, always {@link PokerTicketStatus#VOTING} — no other
     * status is ever settable at creation time.
     *
     * @param roomId    the owning room's identifier
     * @param title     the ticket's display title
     * @param createdAt creation timestamp
     */
    public PokerTicket(final UUID roomId, final String title, final Instant createdAt) {
        this.roomId = roomId;
        this.title = title;
        this.status = PokerTicketStatus.VOTING;
        this.createdAt = createdAt;
    }

    /** @return database primary key */
    public UUID getId() {
        return id;
    }

    /** @return the owning room's identifier */
    public UUID getRoomId() {
        return roomId;
    }

    /** @return the ticket's display title */
    public String getTitle() {
        return title;
    }

    /** @return the ticket's current lifecycle status */
    public PokerTicketStatus getStatus() {
        return status;
    }

    /** @return the creation timestamp */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return the revelation timestamp, or {@code null} while still {@link PokerTicketStatus#VOTING} */
    public Instant getRevealedAt() {
        return revealedAt;
    }
}
