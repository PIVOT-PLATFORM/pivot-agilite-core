package fr.pivot.agilite.poker;

import fr.pivot.agilite.poker.dto.JoinRoomResponse;
import fr.pivot.agilite.poker.dto.RoomResponse;
import fr.pivot.agilite.poker.exception.InviteCodeNotFoundException;
import fr.pivot.agilite.poker.exception.RoomNotFoundException;
import fr.pivot.agilite.poker.ws.PokerRoomDestinations;
import fr.pivot.agilite.poker.ws.RoomAccessGrantService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Business logic for planning poker room creation, lookup, and join-by-code (US09.1.1, US09.1.2).
 */
@Service
public class PokerRoomService {

    /**
     * Maximum attempts to find a free invite code before giving up — collision is
     * near-impossible (see {@link InviteCodeGenerator}, ~1.07 billion combinations).
     */
    private static final int MAX_INVITE_CODE_ATTEMPTS = 5;

    private final PokerRoomRepository repository;
    private final Clock clock;
    private final int defaultExpirationHours;
    private final RoomAccessGrantService roomAccessGrantService;

    /**
     * Constructs the service.
     *
     * @param repository             the room persistence repository
     * @param clock                  the clock used to timestamp rooms (overridable in tests)
     * @param defaultExpirationHours the default room lifetime in hours when the caller omits
     *                               {@code expirationHours} (property {@code
     *                               pivot.agilite.poker.room.default-expiration-hours}, 24 by
     *                               default)
     * @param roomAccessGrantService issues the room-scoped WebSocket access grant minted on a
     *                               successful join (US09.1.2)
     */
    public PokerRoomService(
            final PokerRoomRepository repository,
            final Clock clock,
            @Value("${pivot.agilite.poker.room.default-expiration-hours:24}") final int defaultExpirationHours,
            final RoomAccessGrantService roomAccessGrantService) {
        this.repository = repository;
        this.clock = clock;
        this.defaultExpirationHours = defaultExpirationHours;
        this.roomAccessGrantService = roomAccessGrantService;
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
    public RoomResponse findById(final UUID roomId, final Long tenantId) {
        final PokerRoom room = repository.findByIdAndTenantId(roomId, tenantId)
                .orElseThrow(() -> new RoomNotFoundException(roomId));
        return toResponse(room);
    }

    /**
     * Resolves an invite code into a joinable room and mints a WebSocket access grant for the
     * caller (US09.1.2).
     *
     * <p>Security AC (ADR-026 §2): an unknown code, a code belonging to a room in a different
     * tenant, a code for a deactivated room, and a code for an expired room are all collapsed
     * into the exact same {@link InviteCodeNotFoundException} — never distinguished — so a
     * caller can never learn which of the four applies, nor confirm cross-tenant existence.
     *
     * @param code     the 6-character invite code (already validated by the controller)
     * @param tenantId the caller's tenant id, resolved server-side from the bearer token
     * @return the join response, including a freshly minted {@code accessToken}
     * @throws InviteCodeNotFoundException if the code does not resolve to a room currently
     *                                     joinable by this tenant
     */
    @Transactional
    public JoinRoomResponse join(final String code, final Long tenantId) {
        final Instant now = clock.instant();
        final PokerRoom room = repository.findByInviteCode(code)
                .filter(candidate -> candidate.getTenantId().equals(tenantId))
                .filter(PokerRoom::isActive)
                .filter(candidate -> candidate.getExpiresAt().isAfter(now))
                .orElseThrow(InviteCodeNotFoundException::new);

        final String accessToken = UUID.randomUUID().toString();
        final Duration ttl = Duration.between(now, room.getExpiresAt());
        roomAccessGrantService.grantAccess(room.getId(), accessToken, ttl);

        return new JoinRoomResponse(
                room.getId(),
                room.getName(),
                room.getSequence(),
                PokerCardDeck.FIBONACCI_VALUES,
                room.isActive(),
                room.getExpiresAt(),
                PokerRoomDestinations.roomTopic(room.getId()),
                accessToken);
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
                PokerRoomDestinations.roomTopic(room.getId()));
    }
}
