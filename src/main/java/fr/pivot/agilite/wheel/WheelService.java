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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Business logic for wheel operations (US14.1.1).
 *
 * <p>All read operations are wrapped in a read-only transaction; write operations use a full
 * read-write transaction. The service enforces tenant/team-membership isolation and validates
 * entries (non-empty, no duplicates, valid team-member references) for every write.
 */
@Service
@Transactional(readOnly = true)
public class WheelService {

    private final WheelRepository wheelRepository;
    private final TeamMembershipService teamMembershipService;
    private final PlatformTeamMemberReadRepository teamMemberRepository;
    private final PlatformUserReadRepository userRepository;

    /**
     * Creates the service with all required dependencies.
     *
     * @param wheelRepository        repository for wheel persistence
     * @param teamMembershipService  shared team-resolution/membership-check + display-name helper
     * @param teamMemberRepository   read-only access to {@code public.team_members}, for
     *                               validating a {@code team_member} entry's reference
     * @param userRepository         read-only access to {@code public.users}, for resolving a
     *                               {@code team_member} entry's display label
     */
    public WheelService(
            final WheelRepository wheelRepository,
            final TeamMembershipService teamMembershipService,
            final PlatformTeamMemberReadRepository teamMemberRepository,
            final PlatformUserReadRepository userRepository) {
        this.wheelRepository = wheelRepository;
        this.teamMembershipService = teamMembershipService;
        this.teamMemberRepository = teamMemberRepository;
        this.userRepository = userRepository;
    }

    /**
     * Creates a new wheel with its entries.
     *
     * @param teamId    the owning team's {@code public.teams.id}
     * @param name      the wheel name (1-100 chars, validated at the controller layer)
     * @param entries   the requested entries (non-empty, validated at the controller layer)
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId  the calling tenant's {@code public.tenants.id}
     * @return the created wheel as a response record
     * @throws fr.pivot.agilite.exception.TeamNotFoundException if the team does not exist,
     *     belongs to another tenant, or the caller is not one of its members
     * @throws WheelValidationException if the entries contain duplicates or an invalid
     *     team-member reference
     */
    @Transactional
    public WheelResponse create(
            final Long teamId,
            final String name,
            final List<WheelEntryRequest> entries,
            final Long callerUserId,
            final Long tenantId) {
        PlatformTeam team = teamMembershipService.resolveTeamForCaller(teamId, callerUserId, tenantId);
        Instant now = Instant.now();
        Wheel wheel = new Wheel(tenantId, team.getId(), name, callerUserId, now);
        List<WheelEntry> builtEntries = buildEntries(entries, wheel, team.getId(), now);
        wheel.getEntries().addAll(builtEntries);
        Wheel saved = wheelRepository.save(wheel);
        return WheelResponse.from(saved);
    }

    /**
     * Lists all wheels belonging to a team.
     *
     * @param teamId       the team's {@code public.teams.id}
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the team's wheels (no pagination — small expected volume per team)
     * @throws fr.pivot.agilite.exception.TeamNotFoundException if the team does not exist,
     *     belongs to another tenant, or the caller is not one of its members
     */
    public List<WheelResponse> findAllForTeam(final Long teamId, final Long callerUserId, final Long tenantId) {
        teamMembershipService.resolveTeamForCaller(teamId, callerUserId, tenantId);
        return wheelRepository.findAllByTeamIdAndTenantId(teamId, tenantId).stream()
                .map(WheelResponse::from)
                .toList();
    }

    /**
     * Returns a single wheel if the caller has access to it.
     *
     * @param wheelId  the wheel UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId the calling tenant's {@code public.tenants.id}
     * @return the wheel response
     * @throws WheelNotFoundException if the wheel does not exist, belongs to another tenant, or
     *     the caller is not a member of its team
     */
    public WheelResponse findById(final UUID wheelId, final Long callerUserId, final Long tenantId) {
        Wheel wheel = resolveAccessibleWheel(wheelId, callerUserId, tenantId);
        return WheelResponse.from(wheel);
    }

    /**
     * Fully replaces a wheel's name and entries.
     *
     * @param wheelId      the wheel UUID
     * @param name         the new wheel name (1-100 chars, validated at the controller layer)
     * @param entries      the new entries, replacing all existing ones
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the updated wheel response
     * @throws WheelNotFoundException if the wheel does not exist, belongs to another tenant, or
     *     the caller is not a member of its team
     * @throws WheelValidationException if the entries contain duplicates or an invalid
     *     team-member reference
     */
    @Transactional
    public WheelResponse update(
            final UUID wheelId,
            final String name,
            final List<WheelEntryRequest> entries,
            final Long callerUserId,
            final Long tenantId) {
        Wheel wheel = resolveAccessibleWheel(wheelId, callerUserId, tenantId);
        wheel.setName(name);
        List<WheelEntry> builtEntries = buildEntries(entries, wheel, wheel.getTeamId(), Instant.now());
        wheel.getEntries().clear();
        wheel.getEntries().addAll(builtEntries);
        Wheel saved = wheelRepository.save(wheel);
        return WheelResponse.from(saved);
    }

    /**
     * Permanently deletes a wheel and all its entries.
     *
     * @param wheelId      the wheel UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @throws WheelNotFoundException if the wheel does not exist, belongs to another tenant, or
     *     the caller is not a member of its team
     */
    @Transactional
    public void delete(final UUID wheelId, final Long callerUserId, final Long tenantId) {
        Wheel wheel = resolveAccessibleWheel(wheelId, callerUserId, tenantId);
        wheelRepository.delete(wheel);
    }

    /**
     * Resolves a wheel by id and tenant, then verifies the caller is a member of its team.
     *
     * @param wheelId      the wheel UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the resolved wheel
     * @throws WheelNotFoundException if the wheel does not exist, belongs to another tenant, or
     *     the caller is not a member of its team
     */
    private Wheel resolveAccessibleWheel(final UUID wheelId, final Long callerUserId, final Long tenantId) {
        Wheel wheel = wheelRepository.findByIdAndTenantId(wheelId, tenantId)
                .orElseThrow(() -> new WheelNotFoundException(wheelId));
        if (!teamMemberRepository.existsByTeamIdAndUserId(wheel.getTeamId(), callerUserId)) {
            throw new WheelNotFoundException(wheelId);
        }
        return wheel;
    }

    /**
     * Validates and builds the {@link WheelEntry} list for a create/update request: rejects
     * duplicate {@code teamMemberId} references, duplicate {@code free_text} labels
     * (case/whitespace-insensitive), and invalid team-member references; resolves the display
     * label of every {@code team_member} entry server-side, ignoring any client-supplied label.
     *
     * @param requests the requested entries
     * @param wheel    the owning wheel (already persisted or about to be)
     * @param teamId   the wheel's team, entries must reference a member of this team
     * @param now      timestamp used for the new entries
     * @return the built, validated entries
     * @throws WheelValidationException if a duplicate or invalid reference is found
     */
    private List<WheelEntry> buildEntries(
            final List<WheelEntryRequest> requests, final Wheel wheel, final Long teamId, final Instant now) {
        Set<Long> seenTeamMemberIds = new HashSet<>();
        Set<String> seenFreeTextLabels = new HashSet<>();
        List<WheelEntry> built = new ArrayList<>(requests.size());

        for (WheelEntryRequest request : requests) {
            int weight = request.weight() != null ? request.weight() : 1;
            if (request.type() == WheelEntryType.TEAM_MEMBER) {
                built.add(buildTeamMemberEntry(request, wheel, teamId, weight, now, seenTeamMemberIds));
            } else {
                built.add(buildFreeTextEntry(request, wheel, weight, now, seenFreeTextLabels));
            }
        }
        return built;
    }

    /**
     * Validates and builds a single {@code team_member} entry.
     *
     * @param request            the requested entry
     * @param wheel              the owning wheel
     * @param teamId             the wheel's team
     * @param weight             the resolved weight (defaulted if omitted)
     * @param now                timestamp for the new entry
     * @param seenTeamMemberIds  team-member ids already seen in this request, for duplicate
     *                           detection — mutated by this call
     * @return the built entry
     */
    private WheelEntry buildTeamMemberEntry(
            final WheelEntryRequest request,
            final Wheel wheel,
            final Long teamId,
            final int weight,
            final Instant now,
            final Set<Long> seenTeamMemberIds) {
        Long teamMemberId = request.teamMemberId();
        if (teamMemberId == null) {
            throw new WheelValidationException("INVALID_ENTRY", "teamMemberId is required for a team_member entry");
        }
        if (!seenTeamMemberIds.add(teamMemberId)) {
            throw new WheelValidationException("DUPLICATE_ENTRY", "Duplicate teamMemberId: " + teamMemberId);
        }
        PlatformTeamMember teamMember = teamMemberRepository.findByIdAndTeamId(teamMemberId, teamId)
                .orElseThrow(() -> new WheelValidationException(
                        "INVALID_ENTRY_TEAM_MEMBER", "teamMemberId does not belong to this team: " + teamMemberId));
        PlatformUser user = userRepository.findById(teamMember.getUserId())
                .orElseThrow(() -> new WheelValidationException(
                        "INVALID_ENTRY_TEAM_MEMBER", "team member's user could not be resolved"));
        String label = teamMembershipService.resolveDisplayName(user);
        return new WheelEntry(wheel, WheelEntryType.TEAM_MEMBER, teamMemberId, label, weight, now);
    }

    /**
     * Validates and builds a single {@code free_text} entry.
     *
     * @param request             the requested entry
     * @param wheel               the owning wheel
     * @param weight              the resolved weight (defaulted if omitted)
     * @param now                 timestamp for the new entry
     * @param seenFreeTextLabels  normalized labels already seen in this request, for duplicate
     *                            detection — mutated by this call
     * @return the built entry
     */
    private WheelEntry buildFreeTextEntry(
            final WheelEntryRequest request,
            final Wheel wheel,
            final int weight,
            final Instant now,
            final Set<String> seenFreeTextLabels) {
        if (request.teamMemberId() != null) {
            throw new WheelValidationException("INVALID_ENTRY", "teamMemberId must be omitted for a free_text entry");
        }
        String label = request.label() != null ? request.label().trim() : "";
        if (label.isEmpty() || label.length() > 150) {
            throw new WheelValidationException("INVALID_ENTRY", "label must be 1-150 characters for a free_text entry");
        }
        String normalized = label.toLowerCase(Locale.ROOT);
        if (!seenFreeTextLabels.add(normalized)) {
            throw new WheelValidationException("DUPLICATE_ENTRY", "Duplicate free_text label: " + label);
        }
        return new WheelEntry(wheel, WheelEntryType.FREE_TEXT, null, label, weight, now);
    }
}
