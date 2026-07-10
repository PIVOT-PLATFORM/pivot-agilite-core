package fr.pivot.agilite.wheel;

import fr.pivot.agilite.auth.repository.PlatformTeamMemberReadRepository;
import fr.pivot.agilite.exception.WheelEmptyException;
import fr.pivot.agilite.exception.WheelNotFoundException;
import fr.pivot.agilite.exception.WheelValidationException;
import fr.pivot.agilite.wheel.dto.WheelDrawResponse;
import fr.pivot.agilite.wheel.dto.WheelSpinResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WheelDrawService} (US14.2.1), focused on the anti-repeat wiring, error
 * paths, and history retrieval — the pure weighted-selection statistics are covered separately by
 * {@link WeightedEntrySelectorTest} without any Spring/Mockito wiring.
 *
 * <p>Full end-to-end HTTP/persistence coverage (real Postgres via Testcontainers, cross-tenant
 * isolation) is in {@code WheelSpinControllerIT}.
 */
@ExtendWith(MockitoExtension.class)
class WheelDrawServiceTest {

    private static final Long TENANT_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long CALLER_USER_ID = 100L;

    @Mock
    private WheelRepository wheelRepository;

    @Mock
    private PlatformTeamMemberReadRepository teamMemberRepository;

    @Mock
    private WheelDrawRepository wheelDrawRepository;

    private WheelDrawService wheelDrawService;

    @BeforeEach
    void setUp() {
        wheelDrawService = new WheelDrawService(wheelRepository, teamMemberRepository, wheelDrawRepository);
        lenient().when(wheelRepository.save(any(Wheel.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, CALLER_USER_ID)).thenReturn(true);
    }

    @Test
    void spin_onWheelWithSingleEntry_returnsThatEntryAndUpdatesLastDrawnEntryId() {
        Wheel wheel = wheelWithEntries(1);
        WheelEntry onlyEntry = wheel.getEntries().get(0);
        when(wheelRepository.findByIdAndTenantId(wheel.getId(), TENANT_ID)).thenReturn(Optional.of(wheel));

        WheelSpinResponse response = wheelDrawService.spin(wheel.getId(), null, CALLER_USER_ID, TENANT_ID);

        assertThat(response.wheelId()).isEqualTo(wheel.getId());
        assertThat(response.entryId()).isEqualTo(onlyEntry.getId());
        assertThat(response.label()).isEqualTo(onlyEntry.getLabel());
        assertThat(response.antiRepeatMode()).isEqualTo("reduced_weight");
        assertThat(wheel.getLastDrawnEntryId()).isEqualTo(onlyEntry.getId());

        ArgumentCaptor<WheelDraw> drawCaptor = ArgumentCaptor.forClass(WheelDraw.class);
        verify(wheelDrawRepository).save(drawCaptor.capture());
        WheelDraw persisted = drawCaptor.getValue();
        assertThat(persisted.getWheelId()).isEqualTo(wheel.getId());
        assertThat(persisted.getEntryId()).isEqualTo(onlyEntry.getId());
        assertThat(persisted.getEntryLabel()).isEqualTo(onlyEntry.getLabel());
        assertThat(persisted.getDrawnAt()).isNotNull();
    }

    @Test
    void spin_withNullAntiRepeatMode_defaultsToReducedWeight() {
        Wheel wheel = wheelWithEntries(2);
        when(wheelRepository.findByIdAndTenantId(wheel.getId(), TENANT_ID)).thenReturn(Optional.of(wheel));

        WheelSpinResponse response = wheelDrawService.spin(wheel.getId(), null, CALLER_USER_ID, TENANT_ID);

        assertThat(response.antiRepeatMode()).isEqualTo("reduced_weight");
    }

    @Test
    void spin_withExplicitExcludeMode_isHonored() {
        Wheel wheel = wheelWithEntries(2);
        when(wheelRepository.findByIdAndTenantId(wheel.getId(), TENANT_ID)).thenReturn(Optional.of(wheel));

        WheelSpinResponse response = wheelDrawService.spin(wheel.getId(), "exclude", CALLER_USER_ID, TENANT_ID);

        assertThat(response.antiRepeatMode()).isEqualTo("exclude");
    }

    @Test
    void spin_withInvalidAntiRepeatMode_throwsInvalidAntiRepeatMode() {
        Wheel wheel = wheelWithEntries(1);
        when(wheelRepository.findByIdAndTenantId(wheel.getId(), TENANT_ID)).thenReturn(Optional.of(wheel));

        assertThatThrownBy(() -> wheelDrawService.spin(wheel.getId(), "not_a_mode", CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(WheelValidationException.class)
                .satisfies(ex -> assertThat(((WheelValidationException) ex).getCode())
                        .isEqualTo("INVALID_ANTI_REPEAT_MODE"));
        verify(wheelDrawRepository, never()).save(any());
    }

    @Test
    void spin_onWheelWithNoEntries_throwsWheelEmpty() {
        Wheel wheel = new Wheel(TENANT_ID, TEAM_ID, "Roue vide", CALLER_USER_ID, Instant.now());
        ReflectionTestUtils.setField(wheel, "id", UUID.randomUUID());
        when(wheelRepository.findByIdAndTenantId(wheel.getId(), TENANT_ID)).thenReturn(Optional.of(wheel));

        assertThatThrownBy(() -> wheelDrawService.spin(wheel.getId(), null, CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(WheelEmptyException.class);
        verify(wheelDrawRepository, never()).save(any());
    }

    @Test
    void spin_onWheelNotOwnedByCallersTeam_throwsWheelNotFound() {
        UUID wheelId = UUID.randomUUID();
        when(wheelRepository.findByIdAndTenantId(wheelId, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> wheelDrawService.spin(wheelId, null, CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(WheelNotFoundException.class);
    }

    @Test
    void spin_whenCallerNotMemberOfWheelsTeam_throwsWheelNotFound() {
        Wheel wheel = wheelWithEntries(1);
        when(wheelRepository.findByIdAndTenantId(wheel.getId(), TENANT_ID)).thenReturn(Optional.of(wheel));
        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, CALLER_USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> wheelDrawService.spin(wheel.getId(), null, CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(WheelNotFoundException.class);
        verify(wheelDrawRepository, never()).save(any());
    }

    @Test
    void listDraws_withoutLimit_defaultsToTwenty() {
        Wheel wheel = wheelWithEntries(1);
        when(wheelRepository.findByIdAndTenantId(wheel.getId(), TENANT_ID)).thenReturn(Optional.of(wheel));
        when(wheelDrawRepository.findByWheelIdOrderByDrawnAtDesc(eq(wheel.getId()), any(Pageable.class)))
                .thenReturn(List.of());

        wheelDrawService.listDraws(wheel.getId(), null, CALLER_USER_ID, TENANT_ID);

        verify(wheelDrawRepository).findByWheelIdOrderByDrawnAtDesc(wheel.getId(), PageRequest.of(0, 20));
    }

    @Test
    void listDraws_withValidLimit_isPassedThrough() {
        Wheel wheel = wheelWithEntries(1);
        when(wheelRepository.findByIdAndTenantId(wheel.getId(), TENANT_ID)).thenReturn(Optional.of(wheel));
        when(wheelDrawRepository.findByWheelIdOrderByDrawnAtDesc(eq(wheel.getId()), any(Pageable.class)))
                .thenReturn(List.of());

        wheelDrawService.listDraws(wheel.getId(), "5", CALLER_USER_ID, TENANT_ID);

        verify(wheelDrawRepository).findByWheelIdOrderByDrawnAtDesc(wheel.getId(), PageRequest.of(0, 5));
    }

    @Test
    void listDraws_withNonIntegerLimit_throwsInvalidLimit() {
        Wheel wheel = wheelWithEntries(1);
        when(wheelRepository.findByIdAndTenantId(wheel.getId(), TENANT_ID)).thenReturn(Optional.of(wheel));

        assertThatThrownBy(() -> wheelDrawService.listDraws(wheel.getId(), "abc", CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(WheelValidationException.class)
                .satisfies(ex -> assertThat(((WheelValidationException) ex).getCode()).isEqualTo("INVALID_LIMIT"));
    }

    @Test
    void listDraws_withLimitOutOfRange_throwsInvalidLimit() {
        Wheel wheel = wheelWithEntries(1);
        when(wheelRepository.findByIdAndTenantId(wheel.getId(), TENANT_ID)).thenReturn(Optional.of(wheel));

        assertThatThrownBy(() -> wheelDrawService.listDraws(wheel.getId(), "0", CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(WheelValidationException.class)
                .satisfies(ex -> assertThat(((WheelValidationException) ex).getCode()).isEqualTo("INVALID_LIMIT"));
        assertThatThrownBy(() -> wheelDrawService.listDraws(wheel.getId(), "101", CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(WheelValidationException.class)
                .satisfies(ex -> assertThat(((WheelValidationException) ex).getCode()).isEqualTo("INVALID_LIMIT"));
    }

    @Test
    void listDraws_mapsRepositoryRowsToResponses() {
        Wheel wheel = wheelWithEntries(1);
        UUID entryId = wheel.getEntries().get(0).getId();
        Instant drawnAt = Instant.now();
        WheelDraw draw = new WheelDraw(wheel.getId(), entryId, "Ada Lovelace", drawnAt);
        when(wheelRepository.findByIdAndTenantId(wheel.getId(), TENANT_ID)).thenReturn(Optional.of(wheel));
        when(wheelDrawRepository.findByWheelIdOrderByDrawnAtDesc(eq(wheel.getId()), any(Pageable.class)))
                .thenReturn(List.of(draw));

        List<WheelDrawResponse> responses = wheelDrawService.listDraws(wheel.getId(), null, CALLER_USER_ID, TENANT_ID);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).entryId()).isEqualTo(entryId);
        assertThat(responses.get(0).label()).isEqualTo("Ada Lovelace");
        assertThat(responses.get(0).drawnAt()).isEqualTo(drawnAt);
    }

    /**
     * Builds a wheel owned by {@link #TEAM_ID}/{@link #TENANT_ID} with {@code entryCount}
     * free-text entries of weight 1 each.
     *
     * <p>{@code id} is {@code @GeneratedValue}-managed by JPA and stays {@code null} on an entity
     * built via the plain constructor without an actual persistence context — assigned here via
     * {@link ReflectionTestUtils} (both on the wheel and on each entry) so that mocked repository
     * lookups and response assertions exercise real, distinct identifiers instead of vacuously
     * comparing {@code null} to {@code null}.
     */
    private static Wheel wheelWithEntries(final int entryCount) {
        Instant now = Instant.now();
        Wheel wheel = new Wheel(TENANT_ID, TEAM_ID, "Roue de test", CALLER_USER_ID, now);
        ReflectionTestUtils.setField(wheel, "id", UUID.randomUUID());
        for (int i = 0; i < entryCount; i++) {
            WheelEntry entry = new WheelEntry(wheel, WheelEntryType.FREE_TEXT, null, "Entrant " + i, 1, now);
            ReflectionTestUtils.setField(entry, "id", UUID.randomUUID());
            wheel.getEntries().add(entry);
        }
        return wheel;
    }
}
