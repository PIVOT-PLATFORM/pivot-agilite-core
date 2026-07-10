package fr.pivot.agilite.poker;

import fr.pivot.agilite.poker.dto.AnonymousJoinResponse;
import fr.pivot.agilite.poker.dto.GuestHeartbeatResponse;
import fr.pivot.agilite.poker.dto.JoinRoomResponse;
import fr.pivot.agilite.poker.dto.RoomResponse;
import fr.pivot.agilite.poker.exception.GuestSessionExpiredException;
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
import java.util.Locale;
import java.util.UUID;

/**
 * Business logic for planning poker room creation, lookup, join-by-code (US09.1.1, US09.1.2), and
 * anonymous guest participation (US09.3.1).
 */
@Service
public class PokerRoomService {

    /**
     * Maximum attempts to find a free invite code before giving up — collision is
     * near-impossible (see {@link InviteCodeGenerator}, ~1.07 billion combinations).
     */
    private static final int MAX_INVITE_CODE_ATTEMPTS = 5;

    /**
     * Guest (anonymous) session lifetime cap — 2h of inactivity (ADR-026 §2, US09.3.1). Never
     * lets the session outlive the room itself — see {@link #cappedGuestTtl}.
     */
    private static final Duration GUEST_SESSION_TTL = Duration.ofHours(2);

    /** Prefix for a server-generated pseudonym when the caller supplies none (US09.3.1). */
    private static final String GENERATED_PSEUDONYM_PREFIX = "Invité-";

    /** Length, in characters, of the random suffix appended to a generated pseudonym. */
    private static final int GENERATED_PSEUDONYM_SUFFIX_LENGTH = 4;

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
     * Resolves an invite code into a joinable room for a caller with <strong>no account at
     * all</strong> and mints an anonymous guest access grant (US09.3.1, ADR-026 §2).
     *
     * <p>Unlike {@link #join}, there is no tenant to filter by — an unknown code, a code
     * belonging to a deactivated room, and a code for an expired room all collapse into the same
     * {@link InviteCodeNotFoundException} as the authenticated join flow. No row is written to
     * any table for this participant: {@code sessionId} is generated and returned, never
     * persisted.
     *
     * @param code      the 6-character invite code (already validated by the controller)
     * @param pseudonym the caller-supplied display name, or {@code null}/blank to generate one
     * @return the anonymous join response, including a freshly minted {@code accessToken},
     *         {@code sessionId}, and the resolved pseudonym
     * @throws InviteCodeNotFoundException if the code does not resolve to a room currently
     *                                     joinable anonymously
     */
    @Transactional
    public AnonymousJoinResponse joinAnonymous(final String code, final String pseudonym) {
        final Instant now = clock.instant();
        final PokerRoom room = repository.findByInviteCode(code)
                .filter(PokerRoom::isActive)
                .filter(candidate -> candidate.getExpiresAt().isAfter(now))
                .orElseThrow(InviteCodeNotFoundException::new);

        final String accessToken = UUID.randomUUID().toString();
        final String sessionId = UUID.randomUUID().toString();
        final Duration ttl = cappedGuestTtl(now, room.getExpiresAt());
        roomAccessGrantService.grantGuestAccess(room.getId(), accessToken, ttl);

        return new AnonymousJoinResponse(
                room.getId(),
                room.getName(),
                room.getSequence(),
                PokerCardDeck.FIBONACCI_VALUES,
                room.isActive(),
                room.getExpiresAt(),
                PokerRoomDestinations.roomTopic(room.getId()),
                accessToken,
                sessionId,
                resolvePseudonym(pseudonym),
                now.plus(ttl));
    }

    /**
     * Refreshes an anonymous guest session's access grant, extending it past its current TTL
     * (US09.3.1) — the heartbeat mechanism realizing the "2h of inactivity" expiration AC: as
     * long as the frontend calls this periodically, the grant never lapses; if it stops calling
     * (tab closed, network lost), the grant simply expires via its Redis TTL, exactly as it
     * already does for authenticated participants (EN09.1).
     *
     * @param roomId      the room id from the path
     * @param accessToken the guest's access token, issued by {@link #joinAnonymous}
     * @return the refreshed expiry
     * @throws GuestSessionExpiredException if the token does not currently hold a valid guest
     *                                      grant for this room, or the room itself is no longer
     *                                      active/not expired — never distinguished
     */
    @Transactional
    public GuestHeartbeatResponse refreshGuestSession(final UUID roomId, final String accessToken) {
        final Instant now = clock.instant();
        if (!roomAccessGrantService.isGuest(roomId, accessToken)) {
            throw new GuestSessionExpiredException();
        }
        final PokerRoom room = repository.findById(roomId)
                .filter(PokerRoom::isActive)
                .filter(candidate -> candidate.getExpiresAt().isAfter(now))
                .orElseThrow(GuestSessionExpiredException::new);

        final Duration ttl = cappedGuestTtl(now, room.getExpiresAt());
        roomAccessGrantService.grantGuestAccess(roomId, accessToken, ttl);
        return new GuestHeartbeatResponse(now.plus(ttl));
    }

    /**
     * Computes the guest session TTL: 2h of inactivity, but never past the room's own
     * {@code expiresAt} — a guest session can never outlive the room it belongs to.
     *
     * @param now           the current instant
     * @param roomExpiresAt the room's expiry timestamp
     * @return the smaller of {@link #GUEST_SESSION_TTL} and the time remaining until
     *         {@code roomExpiresAt}
     */
    private Duration cappedGuestTtl(final Instant now, final Instant roomExpiresAt) {
        final Duration untilRoomExpiry = Duration.between(now, roomExpiresAt);
        return untilRoomExpiry.compareTo(GUEST_SESSION_TTL) < 0 ? untilRoomExpiry : GUEST_SESSION_TTL;
    }

    /**
     * Resolves the pseudonym to use for an anonymous join: the caller-supplied value (trimmed,
     * control characters stripped as defense in depth — the pseudonym is inert display data,
     * never interpreted server-side) if non-blank, otherwise a freshly generated default.
     *
     * @param pseudonym the caller-supplied pseudonym, possibly {@code null} or blank
     * @return the resolved, non-blank pseudonym
     */
    private String resolvePseudonym(final String pseudonym) {
        if (pseudonym == null || pseudonym.isBlank()) {
            return generatePseudonym();
        }
        return stripControlCharacters(pseudonym.trim());
    }

    /**
     * Generates a default pseudonym of the shape {@code "Invité-XXXX"}.
     *
     * @return a freshly generated pseudonym
     */
    private String generatePseudonym() {
        String suffix = UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, GENERATED_PSEUDONYM_SUFFIX_LENGTH)
                .toUpperCase(Locale.ROOT);
        return GENERATED_PSEUDONYM_PREFIX + suffix;
    }

    /**
     * Strips ISO control characters from a caller-supplied string, in defense in depth (the
     * pseudonym is display-only data, never interpreted or executed server-side).
     *
     * @param value the trimmed, caller-supplied value
     * @return {@code value} with every control character removed
     */
    private String stripControlCharacters(final String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isISOControl(c)) {
                builder.append(c);
            }
        }
        return builder.toString();
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
