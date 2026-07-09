package fr.pivot.agilite.wheel;

import fr.pivot.agilite.auth.entity.PlatformTeam;
import fr.pivot.agilite.auth.entity.PlatformTeamMember;
import fr.pivot.agilite.auth.entity.PlatformUser;
import fr.pivot.agilite.auth.repository.PlatformTeamMemberReadRepository;
import fr.pivot.agilite.auth.repository.PlatformUserReadRepository;
import fr.pivot.agilite.exception.WheelNotFoundException;
import fr.pivot.agilite.exception.WheelValidationException;
import fr.pivot.agilite.team.TeamMembershipService;
import fr.pivot.agilite.wheel.dto.WheelEntryRequest;
import fr.pivot.agilite.wheel.dto.WheelResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WheelService} (US14.1.1), focused on entry validation and label
 * resolution logic that is awkward to exercise exhaustively via full Testcontainers IT
 * (see {@link WheelControllerIT} for the end-to-end happy/error-path coverage).
 */
@ExtendWith(MockitoExtension.class)
class WheelServiceTest {

    private static final Long TENANT_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long CALLER_USER_ID = 100L;

    @Mock
    private WheelRepository wheelRepository;

    @Mock
    private TeamMembershipService teamMembershipService;

    @Mock
    private PlatformTeamMemberReadRepository teamMemberRepository;

    @Mock
    private PlatformUserReadRepository userRepository;

    private WheelService wheelService;

    @BeforeEach
    void setUp() {
        wheelService = new WheelService(wheelRepository, teamMembershipService, teamMemberRepository, userRepository);
        lenient().when(wheelRepository.save(any(Wheel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PlatformTeam team = mockTeam(TEAM_ID, TENANT_ID);
        lenient().when(teamMembershipService.resolveTeamForCaller(TEAM_ID, CALLER_USER_ID, TENANT_ID))
                .thenReturn(team);
    }

    @Test
    void create_withDuplicateTeamMemberEntries_throwsDuplicateEntry() {
        // Duplicate detection happens before the second lookup is even needed, but stub
        // leniently in case the implementation resolves both before comparing.
        PlatformTeamMember teamMember = mockTeamMember(1L, TEAM_ID, 200L);
        PlatformUser user = mockUser(200L, "Ada", "Lovelace", "ada@x.io");
        lenient().when(teamMemberRepository.findByIdAndTeamId(anyLong(), eq(TEAM_ID)))
                .thenReturn(Optional.of(teamMember));
        lenient().when(userRepository.findById(200L)).thenReturn(Optional.of(user));
        lenient().when(teamMembershipService.resolveDisplayName(any())).thenReturn("Ada Lovelace");

        List<WheelEntryRequest> entries = List.of(
                new WheelEntryRequest(WheelEntryType.TEAM_MEMBER, 1L, null, null),
                new WheelEntryRequest(WheelEntryType.TEAM_MEMBER, 1L, null, null));

        assertThatThrownBy(() -> wheelService.create(TEAM_ID, "Roue", entries, CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(WheelValidationException.class)
                .satisfies(ex -> assertThat(((WheelValidationException) ex).getCode()).isEqualTo("DUPLICATE_ENTRY"));
    }

    @Test
    void create_withDuplicateFreeTextLabelsCaseInsensitive_throwsDuplicateEntry() {
        List<WheelEntryRequest> entries = List.of(
                new WheelEntryRequest(WheelEntryType.FREE_TEXT, null, "Alice", null),
                new WheelEntryRequest(WheelEntryType.FREE_TEXT, null, " ALICE ", null));

        assertThatThrownBy(() -> wheelService.create(TEAM_ID, "Roue", entries, CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(WheelValidationException.class)
                .satisfies(ex -> assertThat(((WheelValidationException) ex).getCode()).isEqualTo("DUPLICATE_ENTRY"));
    }

    @Test
    void create_withTeamMemberNotInWheelTeam_throwsInvalidEntryTeamMember() {
        when(teamMemberRepository.findByIdAndTeamId(1L, TEAM_ID)).thenReturn(Optional.empty());

        List<WheelEntryRequest> entries = List.of(new WheelEntryRequest(WheelEntryType.TEAM_MEMBER, 1L, null, null));

        assertThatThrownBy(() -> wheelService.create(TEAM_ID, "Roue", entries, CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(WheelValidationException.class)
                .satisfies(ex -> assertThat(((WheelValidationException) ex).getCode())
                        .isEqualTo("INVALID_ENTRY_TEAM_MEMBER"));
    }

    @Test
    void create_teamMemberEntry_ignoresClientSuppliedLabelAndUsesResolvedDisplayName() {
        // WheelService's own responsibility is to ignore any client-supplied label and use
        // whatever TeamMembershipService.resolveDisplayName resolves — the display-name
        // algorithm itself (first+last vs. email fallback) is covered by
        // TeamMembershipControllerIT and by WheelControllerIT's full-stack happy path.
        PlatformTeamMember teamMember = mockTeamMember(1L, TEAM_ID, 200L);
        PlatformUser user = mockUser(200L, "Grace", "Hopper", "grace@x.io");
        when(teamMemberRepository.findByIdAndTeamId(1L, TEAM_ID)).thenReturn(Optional.of(teamMember));
        when(userRepository.findById(200L)).thenReturn(Optional.of(user));
        when(teamMembershipService.resolveDisplayName(any())).thenReturn("Grace Hopper");

        List<WheelEntryRequest> entries =
                List.of(new WheelEntryRequest(WheelEntryType.TEAM_MEMBER, 1L, "Spoofed Label", null));

        WheelResponse response = wheelService.create(TEAM_ID, "Roue", entries, CALLER_USER_ID, TENANT_ID);

        assertThat(response.entries()).hasSize(1);
        assertThat(response.entries().get(0).label()).isEqualTo("Grace Hopper");
        assertThat(response.entries().get(0).weight()).isEqualTo(1);
    }

    @Test
    void create_teamMemberEntryWithUnresolvableUser_throwsInvalidEntryTeamMember() {
        PlatformTeamMember teamMember = mockTeamMember(1L, TEAM_ID, 200L);
        when(teamMemberRepository.findByIdAndTeamId(1L, TEAM_ID)).thenReturn(Optional.of(teamMember));
        when(userRepository.findById(200L)).thenReturn(Optional.empty());

        List<WheelEntryRequest> entries = List.of(new WheelEntryRequest(WheelEntryType.TEAM_MEMBER, 1L, null, null));

        assertThatThrownBy(() -> wheelService.create(TEAM_ID, "Roue", entries, CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(WheelValidationException.class)
                .satisfies(ex -> assertThat(((WheelValidationException) ex).getCode())
                        .isEqualTo("INVALID_ENTRY_TEAM_MEMBER"));
    }

    @Test
    void create_freeTextEntryWithBlankLabel_throwsInvalidEntry() {
        List<WheelEntryRequest> entries = List.of(new WheelEntryRequest(WheelEntryType.FREE_TEXT, null, "  ", null));

        assertThatThrownBy(() -> wheelService.create(TEAM_ID, "Roue", entries, CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(WheelValidationException.class)
                .satisfies(ex -> assertThat(((WheelValidationException) ex).getCode()).isEqualTo("INVALID_ENTRY"));
    }

    @Test
    void create_entryWithoutExplicitWeight_defaultsToOne() {
        List<WheelEntryRequest> entries = List.of(new WheelEntryRequest(WheelEntryType.FREE_TEXT, null, "X", null));

        WheelResponse response = wheelService.create(TEAM_ID, "Roue", entries, CALLER_USER_ID, TENANT_ID);

        assertThat(response.entries().get(0).weight()).isEqualTo(1);
    }

    @Test
    void findById_whenWheelNotOwnedByCallersTeam_throwsWheelNotFound() {
        UUID wheelId = UUID.randomUUID();
        when(wheelRepository.findByIdAndTenantId(wheelId, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> wheelService.findById(wheelId, CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(WheelNotFoundException.class);
    }

    private static PlatformTeam mockTeam(final Long id, final Long tenantId) {
        PlatformTeam team = org.mockito.Mockito.mock(PlatformTeam.class);
        lenient().when(team.getId()).thenReturn(id);
        lenient().when(team.getTenantId()).thenReturn(tenantId);
        return team;
    }

    private static PlatformTeamMember mockTeamMember(final Long id, final Long teamId, final Long userId) {
        PlatformTeamMember member = org.mockito.Mockito.mock(PlatformTeamMember.class);
        lenient().when(member.getId()).thenReturn(id);
        lenient().when(member.getTeamId()).thenReturn(teamId);
        lenient().when(member.getUserId()).thenReturn(userId);
        return member;
    }

    private static PlatformUser mockUser(
            final Long id, final String firstName, final String lastName, final String email) {
        PlatformUser user = org.mockito.Mockito.mock(PlatformUser.class);
        lenient().when(user.getId()).thenReturn(id);
        lenient().when(user.getFirstName()).thenReturn(firstName);
        lenient().when(user.getLastName()).thenReturn(lastName);
        lenient().when(user.getEmail()).thenReturn(email);
        return user;
    }
}
