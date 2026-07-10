package fr.pivot.agilite.poker;

import fr.pivot.agilite.poker.dto.AnonymousJoinResponse;
import fr.pivot.agilite.poker.dto.GuestHeartbeatResponse;
import fr.pivot.agilite.poker.dto.JoinRoomResponse;
import fr.pivot.agilite.poker.dto.RoomResponse;
import fr.pivot.agilite.poker.exception.GuestSessionExpiredException;
import fr.pivot.agilite.poker.exception.InviteCodeNotFoundException;
import fr.pivot.agilite.poker.exception.RoomNotFoundException;
import fr.pivot.agilite.poker.ws.PokerParticipantRegistryService;
import fr.pivot.agilite.poker.ws.RoomAccessGrantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PokerRoomService} (US09.1.1, US09.1.2).
 */
@ExtendWith(MockitoExtension.class)
class PokerRoomServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-10T10:00:00Z");
    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_ROOM_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private PokerRoomRepository repository;

    @Mock
    private RoomAccessGrantService roomAccessGrantService;

    @Mock
    private PokerParticipantRegistryService participantRegistryService;

    private PokerRoomService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new PokerRoomService(
                repository, fixedClock, 24, roomAccessGrantService, participantRegistryService);
    }

    /**
     * Given no {@code expirationHours}, when a room is created, then {@code expiresAt} equals
     * {@code createdAt} + 24h (the configured default) and the facilitator/tenant come from the
     * caller's identity, never from client input.
     */
    @Test
    void create_withoutExpirationHours_appliesDefault24h() {
        when(repository.existsByInviteCode(anyString())).thenReturn(false);
        when(repository.save(any(PokerRoom.class))).thenAnswer(invocation -> {
            PokerRoom room = invocation.getArgument(0);
            setId(room, ROOM_ID);
            return room;
        });

        RoomResponse response = service.create("Sprint 8 estimation", 7L, 3L, null);

        assertThat(response.name()).isEqualTo("Sprint 8 estimation");
        assertThat(response.facilitatorUserId()).isEqualTo(7L);
        assertThat(response.createdAt()).isEqualTo(FIXED_NOW);
        assertThat(response.expiresAt()).isEqualTo(FIXED_NOW.plusSeconds(24 * 3600L));
        assertThat(response.active()).isTrue();
        assertThat(response.sequence()).isEqualTo(PokerCardDeck.SEQUENCE_FIBONACCI);
        assertThat(response.cardValues()).isEqualTo(PokerCardDeck.FIBONACCI_VALUES);
        assertThat(response.inviteCode()).hasSize(6);
        assertThat(response.wsTopic()).isEqualTo("/topic/agilite/poker/" + ROOM_ID);
    }

    /**
     * US09.2.1: given a room is created, when the response is built, then the facilitator
     * receives their own freshly minted, non-blank {@code accessToken} — a grant is issued and
     * the facilitator is registered into the room's participant roster with a TTL matching the
     * room's own expiry, exactly mirroring what {@link PokerRoomService#join} already does for a
     * joining participant.
     */
    @Test
    void create_mintsFacilitatorAccessTokenAndRegistersParticipant() {
        when(repository.existsByInviteCode(anyString())).thenReturn(false);
        when(repository.save(any(PokerRoom.class))).thenAnswer(invocation -> {
            PokerRoom room = invocation.getArgument(0);
            setId(room, ROOM_ID);
            return room;
        });

        RoomResponse response = service.create("Sprint 8 estimation", 7L, 3L, 1);

        assertThat(response.accessToken()).isNotBlank();
        Duration expectedTtl = Duration.ofHours(1);
        verify(roomAccessGrantService).grantAccess(eq(ROOM_ID), anyString(), eq(expectedTtl));
        verify(participantRegistryService).register(eq(ROOM_ID), anyString(), eq(expectedTtl));
    }

    /**
     * Given an explicit {@code expirationHours}, when a room is created, then {@code expiresAt}
     * equals {@code createdAt} + that many hours, overriding the default.
     */
    @Test
    void create_withExplicitExpirationHours_overridesDefault() {
        when(repository.existsByInviteCode(anyString())).thenReturn(false);
        when(repository.save(any(PokerRoom.class))).thenAnswer(invocation -> {
            PokerRoom room = invocation.getArgument(0);
            setId(room, ROOM_ID);
            return room;
        });

        RoomResponse response = service.create("Room", 1L, 1L, 1);

        assertThat(response.expiresAt()).isEqualTo(FIXED_NOW.plusSeconds(3600L));
    }

    /**
     * Given an invite-code collision on the first attempt, when a room is created, then the
     * service retries and eventually succeeds with a free code.
     */
    @Test
    void create_inviteCodeCollision_retriesUntilFree() {
        when(repository.existsByInviteCode(anyString())).thenReturn(true, true, false);
        when(repository.save(any(PokerRoom.class))).thenAnswer(invocation -> {
            PokerRoom room = invocation.getArgument(0);
            setId(room, ROOM_ID);
            return room;
        });

        RoomResponse response = service.create("Room", 1L, 1L, null);

        assertThat(response.inviteCode()).isNotNull();
        verify(repository, times(3)).existsByInviteCode(anyString());
    }

    /**
     * Given persistent invite-code collisions beyond the retry budget, when a room is created,
     * then the service gives up with an {@link IllegalStateException} rather than looping
     * forever or silently reusing a code.
     */
    @Test
    void create_persistentInviteCodeCollision_throwsIllegalState() {
        when(repository.existsByInviteCode(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.create("Room", 1L, 1L, null))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Given a room that belongs to the caller's tenant, when found by id, then the mapped
     * response is returned.
     */
    @Test
    void findById_existingRoomInTenant_returnsResponse() {
        PokerRoom room = new PokerRoom(3L, 7L, "Room", "ABC234", FIXED_NOW, FIXED_NOW.plusSeconds(3600));
        setId(room, ROOM_ID);
        when(repository.findByIdAndTenantId(ROOM_ID, 3L)).thenReturn(Optional.of(room));

        RoomResponse response = service.findById(ROOM_ID, 3L);

        assertThat(response.id()).isEqualTo(ROOM_ID);
        assertThat(response.wsTopic()).isEqualTo("/topic/agilite/poker/" + ROOM_ID);
        assertThat(response.accessToken())
                .as("GET must never re-mint a facilitator grant — only the creation response does")
                .isNull();
    }

    /**
     * Error case: given a room id that does not exist for the caller's tenant (either it does
     * not exist at all, or belongs to another tenant), when found by id, then {@link
     * RoomNotFoundException} is thrown.
     */
    @Test
    void findById_notFoundForTenant_throwsRoomNotFoundException() {
        when(repository.findByIdAndTenantId(eq(OTHER_ROOM_ID), eq(3L))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(OTHER_ROOM_ID, 3L))
                .isInstanceOf(RoomNotFoundException.class);
    }

    /**
     * Security: given two different tenants, when the same room id is looked up under each,
     * then the repository is queried scoped by tenant id — a room from tenant A is never
     * returned for a lookup under tenant B (asserted via the exact stubbed arguments: no
     * matching stub for tenant B means {@link RoomNotFoundException}).
     */
    @Test
    void findById_crossTenantLookup_isScopedByTenantId() {
        PokerRoom roomForTenantA = new PokerRoom(1L, 1L, "Room", "ABC234", FIXED_NOW, FIXED_NOW.plusSeconds(3600));
        setId(roomForTenantA, ROOM_ID);
        when(repository.findByIdAndTenantId(ROOM_ID, 1L)).thenReturn(Optional.of(roomForTenantA));
        when(repository.findByIdAndTenantId(ROOM_ID, 2L)).thenReturn(Optional.empty());

        assertThat(service.findById(ROOM_ID, 1L).id()).isEqualTo(ROOM_ID);
        assertThatThrownBy(() -> service.findById(ROOM_ID, 2L)).isInstanceOf(RoomNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // join (US09.1.2)
    // -------------------------------------------------------------------------

    /**
     * Given a valid, active, non-expired invite code belonging to the caller's tenant, when
     * joining, then a fresh access token is minted, {@code RoomAccessGrantService#grantAccess} is
     * called with the room id, a non-blank token, and a TTL computed from the fixed clock against
     * the room's {@code expiresAt}, and the response carries no {@code inviteCode}/{@code
     * facilitatorUserId}.
     */
    @Test
    void join_validActiveCode_mintsTokenAndGrantsAccess() {
        Instant expiresAt = FIXED_NOW.plusSeconds(3600);
        PokerRoom room = new PokerRoom(3L, 7L, "Sprint 8", "ABC234", FIXED_NOW, expiresAt);
        setId(room, ROOM_ID);
        when(repository.findByInviteCode("ABC234")).thenReturn(Optional.of(room));

        JoinRoomResponse response = service.join("ABC234", 3L);

        assertThat(response.roomId()).isEqualTo(ROOM_ID);
        assertThat(response.name()).isEqualTo("Sprint 8");
        assertThat(response.sequence()).isEqualTo(PokerCardDeck.SEQUENCE_FIBONACCI);
        assertThat(response.cardValues()).isEqualTo(PokerCardDeck.FIBONACCI_VALUES);
        assertThat(response.active()).isTrue();
        assertThat(response.expiresAt()).isEqualTo(expiresAt);
        assertThat(response.wsTopic()).isEqualTo("/topic/agilite/poker/" + ROOM_ID);
        assertThat(response.accessToken()).isNotBlank();

        Duration expectedTtl = Duration.between(FIXED_NOW, expiresAt);
        verify(roomAccessGrantService)
                .grantAccess(eq(ROOM_ID), anyString(), eq(expectedTtl));
        verify(participantRegistryService)
                .register(eq(ROOM_ID), anyString(), eq(expectedTtl));
    }

    /**
     * Error case: given an invite code that matches no room at all, when joining, then {@link
     * InviteCodeNotFoundException} is thrown and no access grant is ever issued.
     */
    @Test
    void join_unknownCode_throwsInviteCodeNotFoundException() {
        when(repository.findByInviteCode("ZZZZZZ")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.join("ZZZZZZ", 3L))
                .isInstanceOf(InviteCodeNotFoundException.class);
        verifyNoInteractions(roomAccessGrantService);
        verifyNoInteractions(participantRegistryService);
    }

    /**
     * Security AC (ADR-026 §2): given a code belonging to a room in a different tenant, when
     * joining, then it throws the exact same {@link InviteCodeNotFoundException} as an unknown
     * code — never confirming cross-tenant existence — and no access grant is issued.
     */
    @Test
    void join_crossTenantCode_throwsInviteCodeNotFoundException() {
        PokerRoom room = new PokerRoom(1L, 7L, "Room", "ABC234", FIXED_NOW, FIXED_NOW.plusSeconds(3600));
        setId(room, ROOM_ID);
        when(repository.findByInviteCode("ABC234")).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> service.join("ABC234", 2L))
                .isInstanceOf(InviteCodeNotFoundException.class);
        verifyNoInteractions(roomAccessGrantService);
        verifyNoInteractions(participantRegistryService);
    }

    /**
     * Security AC (ADR-026 §2): given a deactivated room's code, when joining, then it throws
     * the same {@link InviteCodeNotFoundException} and no access grant is issued.
     */
    @Test
    void join_inactiveRoomCode_throwsInviteCodeNotFoundException() {
        PokerRoom room = new PokerRoom(3L, 7L, "Room", "ABC234", FIXED_NOW, FIXED_NOW.plusSeconds(3600));
        setId(room, ROOM_ID);
        setActive(room, false);
        when(repository.findByInviteCode("ABC234")).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> service.join("ABC234", 3L))
                .isInstanceOf(InviteCodeNotFoundException.class);
        verifyNoInteractions(roomAccessGrantService);
        verifyNoInteractions(participantRegistryService);
    }

    /**
     * Security AC (ADR-026 §2): given an expired room's code, when joining, then it throws the
     * same {@link InviteCodeNotFoundException} and no access grant is issued.
     */
    @Test
    void join_expiredRoomCode_throwsInviteCodeNotFoundException() {
        PokerRoom room = new PokerRoom(3L, 7L, "Room", "ABC234", FIXED_NOW.minusSeconds(7200), FIXED_NOW.minusSeconds(3600));
        setId(room, ROOM_ID);
        when(repository.findByInviteCode("ABC234")).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> service.join("ABC234", 3L))
                .isInstanceOf(InviteCodeNotFoundException.class);
        verifyNoInteractions(roomAccessGrantService);
        verifyNoInteractions(participantRegistryService);
    }

    /**
     * Boundary case: a room whose {@code expiresAt} equals exactly the current clock instant is
     * treated as expired ({@code isAfter}, strictly, not {@code isBefore}/{@code equals}) — same
     * indistinguishable exception.
     */
    @Test
    void join_expiresAtExactlyNow_isTreatedAsExpired() {
        PokerRoom room = new PokerRoom(3L, 7L, "Room", "ABC234", FIXED_NOW.minusSeconds(3600), FIXED_NOW);
        setId(room, ROOM_ID);
        when(repository.findByInviteCode("ABC234")).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> service.join("ABC234", 3L))
                .isInstanceOf(InviteCodeNotFoundException.class);
        verifyNoInteractions(roomAccessGrantService);
        verifyNoInteractions(participantRegistryService);
    }

    // -------------------------------------------------------------------------
    // joinAnonymous / refreshGuestSession (US09.3.1)
    // -------------------------------------------------------------------------

    /**
     * Given a valid, active, non-expired invite code, when an anonymous caller joins with no
     * pseudonym, then a guest access grant is minted (not a standard one), a temporary
     * {@code sessionId} is generated, and a default pseudonym of the shape {@code "Invité-XXXX"}
     * is returned.
     */
    @Test
    void joinAnonymous_validCode_grantsGuestAccessAndGeneratesPseudonym() {
        // Room expires in 3h — well past the 2h guest cap, so the cap (not the room's own
        // expiry) is what should apply here; the room-expiring-soon case is covered separately
        // by joinAnonymous_roomExpiringSoon_capsGuestSessionAtRoomExpiry below.
        Instant expiresAt = FIXED_NOW.plusSeconds(10_800);
        PokerRoom room = new PokerRoom(3L, 7L, "Sprint 8", "ABC234", FIXED_NOW, expiresAt);
        setId(room, ROOM_ID);
        when(repository.findByInviteCode("ABC234")).thenReturn(Optional.of(room));

        AnonymousJoinResponse response = service.joinAnonymous("ABC234", null);

        assertThat(response.roomId()).isEqualTo(ROOM_ID);
        assertThat(response.name()).isEqualTo("Sprint 8");
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.sessionId()).isNotBlank();
        assertThat(response.pseudonym()).startsWith("Invité-");
        assertThat(response.guestSessionExpiresAt()).isEqualTo(FIXED_NOW.plus(Duration.ofHours(2)));

        verify(roomAccessGrantService)
                .grantGuestAccess(eq(ROOM_ID), anyString(), eq(Duration.ofHours(2)));
    }

    /**
     * Given a caller-supplied pseudonym, when joining anonymously, then the response carries
     * that exact (trimmed) pseudonym, not a generated one.
     */
    @Test
    void joinAnonymous_withPseudonym_returnsTrimmedPseudonym() {
        PokerRoom room = new PokerRoom(3L, 7L, "Room", "ABC234", FIXED_NOW, FIXED_NOW.plusSeconds(3600));
        setId(room, ROOM_ID);
        when(repository.findByInviteCode("ABC234")).thenReturn(Optional.of(room));

        AnonymousJoinResponse response = service.joinAnonymous("ABC234", "  Alex  ");

        assertThat(response.pseudonym()).isEqualTo("Alex");
    }

    /**
     * Given a room expiring in less than 2h, when joining anonymously, then the guest session
     * expiry is capped at the room's own {@code expiresAt} — never later, so a guest session can
     * never outlive its room.
     */
    @Test
    void joinAnonymous_roomExpiringSoon_capsGuestSessionAtRoomExpiry() {
        Instant expiresAt = FIXED_NOW.plusSeconds(600);
        PokerRoom room = new PokerRoom(3L, 7L, "Room", "ABC234", FIXED_NOW, expiresAt);
        setId(room, ROOM_ID);
        when(repository.findByInviteCode("ABC234")).thenReturn(Optional.of(room));

        AnonymousJoinResponse response = service.joinAnonymous("ABC234", null);

        assertThat(response.guestSessionExpiresAt()).isEqualTo(expiresAt);
        verify(roomAccessGrantService)
                .grantGuestAccess(eq(ROOM_ID), anyString(), eq(Duration.ofSeconds(600)));
    }

    /**
     * Error case: given an invite code that matches no room, when joining anonymously, then
     * {@link InviteCodeNotFoundException} is thrown and no guest grant is ever issued — same
     * unified 404 posture as the authenticated join (US09.1.2).
     */
    @Test
    void joinAnonymous_unknownCode_throwsInviteCodeNotFoundException() {
        when(repository.findByInviteCode("ZZZZZZ")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.joinAnonymous("ZZZZZZ", null))
                .isInstanceOf(InviteCodeNotFoundException.class);
        verifyNoInteractions(roomAccessGrantService);
    }

    /**
     * Error case: given a deactivated room's code, when joining anonymously, then {@link
     * InviteCodeNotFoundException} is thrown and no guest grant is issued.
     */
    @Test
    void joinAnonymous_inactiveRoomCode_throwsInviteCodeNotFoundException() {
        PokerRoom room = new PokerRoom(3L, 7L, "Room", "ABC234", FIXED_NOW, FIXED_NOW.plusSeconds(3600));
        setId(room, ROOM_ID);
        setActive(room, false);
        when(repository.findByInviteCode("ABC234")).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> service.joinAnonymous("ABC234", null))
                .isInstanceOf(InviteCodeNotFoundException.class);
        verifyNoInteractions(roomAccessGrantService);
    }

    /**
     * Error case: given an expired room's code, when joining anonymously, then {@link
     * InviteCodeNotFoundException} is thrown and no guest grant is issued.
     */
    @Test
    void joinAnonymous_expiredRoomCode_throwsInviteCodeNotFoundException() {
        PokerRoom room = new PokerRoom(
                3L, 7L, "Room", "ABC234", FIXED_NOW.minusSeconds(7200), FIXED_NOW.minusSeconds(3600));
        setId(room, ROOM_ID);
        when(repository.findByInviteCode("ABC234")).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> service.joinAnonymous("ABC234", null))
                .isInstanceOf(InviteCodeNotFoundException.class);
        verifyNoInteractions(roomAccessGrantService);
    }

    /**
     * Given a currently valid guest session, when the heartbeat is called, then the guest grant
     * is refreshed (re-granted with a recomputed TTL) and the new expiry is returned.
     */
    @Test
    void refreshGuestSession_validSession_refreshesGrantAndReturnsNewExpiry() {
        Instant expiresAt = FIXED_NOW.plusSeconds(7200);
        PokerRoom room = new PokerRoom(3L, 7L, "Room", "ABC234", FIXED_NOW, expiresAt);
        setId(room, ROOM_ID);
        when(roomAccessGrantService.isGuest(ROOM_ID, "guest-token")).thenReturn(true);
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room));

        GuestHeartbeatResponse response = service.refreshGuestSession(ROOM_ID, "guest-token");

        assertThat(response.expiresAt()).isEqualTo(FIXED_NOW.plus(Duration.ofHours(2)));
        verify(roomAccessGrantService)
                .grantGuestAccess(ROOM_ID, "guest-token", Duration.ofHours(2));
    }

    /**
     * Error case: given a token that does not currently hold a guest grant (never issued,
     * already expired, or belongs to another room), when the heartbeat is called, then {@link
     * GuestSessionExpiredException} is thrown — a clear error, not a silent no-op.
     */
    @Test
    void refreshGuestSession_notAGuestGrant_throwsGuestSessionExpiredException() {
        when(roomAccessGrantService.isGuest(ROOM_ID, "unknown-token")).thenReturn(false);

        assertThatThrownBy(() -> service.refreshGuestSession(ROOM_ID, "unknown-token"))
                .isInstanceOf(GuestSessionExpiredException.class);
    }

    /**
     * Error case: given a guest grant whose room has since been deactivated or expired, when the
     * heartbeat is called, then {@link GuestSessionExpiredException} is thrown — the session
     * cannot outlive its room, ever.
     */
    @Test
    void refreshGuestSession_roomNoLongerActive_throwsGuestSessionExpiredException() {
        PokerRoom room = new PokerRoom(3L, 7L, "Room", "ABC234", FIXED_NOW, FIXED_NOW.plusSeconds(3600));
        setId(room, ROOM_ID);
        setActive(room, false);
        when(roomAccessGrantService.isGuest(ROOM_ID, "guest-token")).thenReturn(true);
        when(repository.findById(ROOM_ID)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> service.refreshGuestSession(ROOM_ID, "guest-token"))
                .isInstanceOf(GuestSessionExpiredException.class);
    }

    private static void setActive(final PokerRoom room, final boolean active) {
        try {
            java.lang.reflect.Field field = PokerRoom.class.getDeclaredField("active");
            field.setAccessible(true);
            field.set(room, active);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setId(final PokerRoom room, final UUID id) {
        try {
            java.lang.reflect.Field field = PokerRoom.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(room, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
