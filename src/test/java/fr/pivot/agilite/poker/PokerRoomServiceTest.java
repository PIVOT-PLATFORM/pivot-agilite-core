package fr.pivot.agilite.poker;

import fr.pivot.agilite.poker.dto.RoomResponse;
import fr.pivot.agilite.poker.exception.RoomNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
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
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PokerRoomService} (US09.1.1).
 */
@ExtendWith(MockitoExtension.class)
class PokerRoomServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-10T10:00:00Z");
    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_ROOM_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private PokerRoomRepository repository;

    private PokerRoomService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new PokerRoomService(repository, fixedClock, 24);
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
