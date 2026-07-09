package fr.pivot.agilite.poker;

import fr.pivot.agilite.poker.dto.RoomResponse;
import fr.pivot.agilite.poker.exception.RoomNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Business logic for planning poker room creation and lookup (US09.1.1).
 */
@Service
public class PokerRoomService {

    /** STOMP destination prefix a room's participants subscribe to (ADR-026 §2, US09.1.2). */
    private static final String WS_TOPIC_PREFIX = "/topic/agilite/poker/";

    /**
     * Maximum attempts to find a free invite code before giving up — collision is
     * near-impossible (see {@link InviteCodeGenerator}, ~1.07 billion combinations).
     */
    private static final int MAX_INVITE_CODE_ATTEMPTS = 5;

    private final PokerRoomRepository repository;
    private final Clock clock;
    private final int defaultExpirationHours;

    /**
     * Constructs the service.
     *
     * @param repository             the room persistence repository
     * @param clock                  the clock used to timestamp rooms (overridable in tests)
     * @param defaultExpirationHours the default room lifetime in hours when the caller omits
     *                               {@code expirationHours} (property {@code
     *                               pivot.agilite.poker.room.default-expiration-hours}, 24 by
     *                               default)
     */
    public PokerRoomService(
            final PokerRoomRepository repository,
            final Clock clock,
            @Value("${pivot.agilite.poker.room.default-expiration-hours:24}") final int defaultExpirationHours) {
        this.repository = repository;
        this.clock = clock;
        this.defaultExpirationHours = defaultExpirationHours;
    }

    /**
     * Creates a new planning poker room. The caller becomes its facilitator automatically.
     *
     * @param name              the room's display name (already validated by the controller)
     * @param facilitatorUserId the caller's user id, resolved server-side from the bearer token
     * @param tenantId          the caller's tenant id, resolved server-side from the bearer token
     * @param expirationHours   optional room lifetime in hours (1-168); {@code null} applies
     *                          {@link #defaultExpirationHours}
     * @return the created room
     */
    @Transactional
    public RoomResponse create(
            final String name,
            final Long facilitatorUserId,
            final Long tenantId,
            final Integer expirationHours) {
        final Instant now = clock.instant();
        final int hours = expirationHours != null ? expirationHours : defaultExpirationHours;
        final String inviteCode = generateUniqueInviteCode();
        final PokerRoom room = new PokerRoom(
                tenantId, facilitatorUserId, name, inviteCode, now, now.plus(hours, ChronoUnit.HOURS));
        return toResponse(repository.save(room));
    }

    /**
     * Finds a room by id, scoped to the caller's tenant.
     *
     * @param roomId   the room id from the path
     * @param tenantId the caller's tenant id, resolved server-side from the bearer token
     * @return the matching room
     * @throws RoomNotFoundException if no room with this id exists for this tenant
     */
    @Transactional(readOnly = true)
    public RoomResponse findById(final Long roomId, final Long tenantId) {
        final PokerRoom room = repository.findByIdAndTenantId(roomId, tenantId)
                .orElseThrow(() -> new RoomNotFoundException(roomId));
        return toResponse(room);
    }

    /**
     * Generates an invite code guaranteed unique at the time of the check, retrying on the rare
     * collision.
     *
     * @return a currently-unused invite code
     * @throws IllegalStateException if no free code is found within {@link
     *     #MAX_INVITE_CODE_ATTEMPTS} attempts
     */
    private String generateUniqueInviteCode() {
        for (int attempt = 0; attempt < MAX_INVITE_CODE_ATTEMPTS; attempt++) {
            final String candidate = InviteCodeGenerator.generate();
            if (!repository.existsByInviteCode(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Unable to generate a unique invite code after " + MAX_INVITE_CODE_ATTEMPTS + " attempts");
    }

    /**
     * Maps a persisted room to its API response shape.
     *
     * @param room the persisted room
     * @return the corresponding {@link RoomResponse}
     */
    private RoomResponse toResponse(final PokerRoom room) {
        return new RoomResponse(
                room.getId(),
                room.getName(),
                room.getInviteCode(),
                room.getSequence(),
                PokerCardDeck.FIBONACCI_VALUES,
                room.getFacilitatorUserId(),
                room.isActive(),
                room.getCreatedAt(),
                room.getExpiresAt(),
                WS_TOPIC_PREFIX + room.getId());
    }
}
