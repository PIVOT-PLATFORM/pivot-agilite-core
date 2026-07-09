package fr.pivot.agilite.poker;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity backing a planning poker room (US09.1.1), table {@code agilite.poker_rooms}.
 *
 * <p>Never exposed directly over the API — {@link PokerRoomController} always returns a {@code
 * RoomResponse} DTO built by {@link PokerRoomService}, per this repo's "no JPA entity in API
 * responses" standard.
 *
 * <p><strong>{@code UUID} primary key, not {@code BIGSERIAL}</strong> — matches
 * {@code fr.pivot.agilite.retro.session.RetroSession} (US20.1.1) and {@code
 * pivot-collaboratif-core}'s {@code Board}: room/session-like resources use {@code UUID} across
 * this platform (unlike {@code public.tenants}/{@code public.users}, which stay {@code BIGSERIAL}
 * — no UUID identity concept there). Required for interop with EN09.1's WebSocket isolation
 * layer: {@link fr.pivot.agilite.poker.ws.PokerRoomDestinations#roomTopic(UUID)} and {@link
 * fr.pivot.agilite.poker.ws.RoomAccessGrantService} are both keyed on {@code UUID roomId}.
 */
@Entity
@Table(name = "poker_rooms", schema = "agilite")
public class PokerRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "facilitator_user_id", nullable = false)
    private Long facilitatorUserId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "invite_code", nullable = false, unique = true, length = 6)
    private String inviteCode;

    @Column(name = "sequence", nullable = false, length = 20)
    private String sequence;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** No-argument constructor required by JPA. */
    protected PokerRoom() {
    }

    /**
     * Creates a new room ready to persist. {@code sequence} is always {@link
     * PokerCardDeck#SEQUENCE_FIBONACCI} in v1 (ADR-026 §2) and {@code active} always starts
     * {@code true} — neither is settable from outside this constructor.
     *
     * @param tenantId          the owning tenant, resolved server-side from the caller's token
     * @param facilitatorUserId the creator's user id, resolved server-side from the caller's
     *                          token
     * @param name              the room's display name
     * @param inviteCode        the generated, pre-checked-unique invite code
     * @param createdAt         creation timestamp
     * @param expiresAt         expiry timestamp
     */
    public PokerRoom(
            final Long tenantId,
            final Long facilitatorUserId,
            final String name,
            final String inviteCode,
            final Instant createdAt,
            final Instant expiresAt) {
        this.tenantId = tenantId;
        this.facilitatorUserId = facilitatorUserId;
        this.name = name;
        this.inviteCode = inviteCode;
        this.sequence = PokerCardDeck.SEQUENCE_FIBONACCI;
        this.active = true;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** @return database primary key */
    public UUID getId() {
        return id;
    }

    /** @return the owning tenant's {@code public.tenants.id} */
    public Long getTenantId() {
        return tenantId;
    }

    /** @return the facilitator's {@code public.users.id} (the room's creator) */
    public Long getFacilitatorUserId() {
        return facilitatorUserId;
    }

    /** @return the room's display name */
    public String getName() {
        return name;
    }

    /** @return the 6-character invite code */
    public String getInviteCode() {
        return inviteCode;
    }

    /** @return the fixed card sequence identifier, always {@code "FIBONACCI"} in v1 */
    public String getSequence() {
        return sequence;
    }

    /** @return {@code true} while the room has not been deactivated */
    public boolean isActive() {
        return active;
    }

    /** @return the creation timestamp */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return the expiry timestamp */
    public Instant getExpiresAt() {
        return expiresAt;
    }
}
