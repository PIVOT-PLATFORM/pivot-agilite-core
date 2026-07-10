package fr.pivot.agilite.wheel;

import fr.pivot.agilite.auth.repository.PlatformTeamMemberReadRepository;
import fr.pivot.agilite.exception.WheelEmptyException;
import fr.pivot.agilite.exception.WheelNotFoundException;
import fr.pivot.agilite.exception.WheelValidationException;
import fr.pivot.agilite.wheel.dto.WheelDrawResponse;
import fr.pivot.agilite.wheel.dto.WheelSpinResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Business logic for the weighted anti-repeat draw (US14.2.1) — {@code POST
 * /wheels/{wheelId}/spin} and {@code GET /wheels/{wheelId}/draws}.
 *
 * <p>Kept as its own service (rather than folded into {@link WheelService}) to keep US14.1.1's
 * CRUD service unchanged and avoid widening a PR that is not merged yet
 * ({@code pivot-agilite-core#27}, hard-blocked pending maintainer review for its first-time
 * {@code pivot-core-starter} dependency). The tenant/team-membership access check below
 * intentionally duplicates {@code WheelService}'s equivalent private method for the same reason.
 */
@Service
@Transactional(readOnly = true)
public class WheelDrawService {

    /** Default number of draws returned by {@code GET .../draws} when {@code limit} is omitted. */
    static final int DEFAULT_DRAW_HISTORY_LIMIT = 20;

    /** Maximum number of draws that {@code GET .../draws} will ever return in one call. */
    static final int MAX_DRAW_HISTORY_LIMIT = 100;

    private final WheelRepository wheelRepository;
    private final PlatformTeamMemberReadRepository teamMemberRepository;
    private final WheelDrawRepository wheelDrawRepository;

    /**
     * Creates the service with all required dependencies.
     *
     * @param wheelRepository      repository for wheel persistence (read + {@code
     *                             lastDrawnEntryId} update)
     * @param teamMemberRepository read-only access to {@code public.team_members}, for verifying
     *                             the caller's membership of the wheel's team
     * @param wheelDrawRepository  repository for draw-history persistence
     */
    public WheelDrawService(
            final WheelRepository wheelRepository,
            final PlatformTeamMemberReadRepository teamMemberRepository,
            final WheelDrawRepository wheelDrawRepository) {
        this.wheelRepository = wheelRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.wheelDrawRepository = wheelDrawRepository;
    }

    /**
     * Performs a weighted, anti-repeat draw on a wheel: selects an entry with probability
     * proportional to its effective weight, records it as the wheel's new
     * {@code lastDrawnEntryId}, and persists a {@link WheelDraw} history row.
     *
     * @param wheelId             the wheel UUID
     * @param rawAntiRepeatMode   the raw {@code antiRepeatMode} from the request body, or
     *                            {@code null} if the field/body was omitted (defaults to {@code
     *                            reduced_weight})
     * @param callerUserId        the calling user's {@code public.users.id}
     * @param tenantId            the calling tenant's {@code public.tenants.id}
     * @return the draw result
     * @throws WheelNotFoundException if the wheel does not exist, belongs to another tenant, or
     *     the caller is not a member of its team
     * @throws WheelEmptyException if the wheel has zero entries (defensive guard)
     * @throws WheelValidationException with code {@code INVALID_ANTI_REPEAT_MODE} if {@code
     *     rawAntiRepeatMode} is neither {@code null} nor a known mode
     */
    @Transactional
    public WheelSpinResponse spin(
            final UUID wheelId,
            final String rawAntiRepeatMode,
            final Long callerUserId,
            final Long tenantId) {
        Wheel wheel = resolveAccessibleWheel(wheelId, callerUserId, tenantId);
        if (wheel.getEntries().isEmpty()) {
            throw new WheelEmptyException(wheelId);
        }
        AntiRepeatMode mode = rawAntiRepeatMode == null
                ? AntiRepeatMode.REDUCED_WEIGHT
                : AntiRepeatMode.fromJsonValue(rawAntiRepeatMode);

        List<WeightedEntrySelector.Candidate> nominal = wheel.getEntries().stream()
                .map(entry -> new WeightedEntrySelector.Candidate(entry.getId(), entry.getLabel(), entry.getWeight()))
                .toList();
        List<WeightedEntrySelector.Candidate> effective =
                WeightedEntrySelector.applyAntiRepeat(nominal, wheel.getLastDrawnEntryId(), mode);
        WeightedEntrySelector.Candidate chosen =
                WeightedEntrySelector.select(effective, ThreadLocalRandom.current());

        Instant now = Instant.now();
        wheel.setLastDrawnEntryId(chosen.entryId());
        wheelRepository.save(wheel);
        wheelDrawRepository.save(new WheelDraw(wheel.getId(), chosen.entryId(), chosen.label(), now));

        return new WheelSpinResponse(wheel.getId(), chosen.entryId(), chosen.label(), now, mode.jsonValue());
    }

    /**
     * Lists the most recent draws of a wheel, most recent first.
     *
     * @param wheelId      the wheel UUID
     * @param rawLimit     the raw {@code limit} query parameter, or {@code null} if omitted
     *                     (defaults to {@value #DEFAULT_DRAW_HISTORY_LIMIT})
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the most recent draws, most recent first, at most {@code limit} elements
     * @throws WheelNotFoundException if the wheel does not exist, belongs to another tenant, or
     *     the caller is not a member of its team
     * @throws WheelValidationException with code {@code INVALID_LIMIT} if {@code rawLimit} is not
     *     a valid integer between 1 and {@value #MAX_DRAW_HISTORY_LIMIT}
     */
    public List<WheelDrawResponse> listDraws(
            final UUID wheelId, final String rawLimit, final Long callerUserId, final Long tenantId) {
        Wheel wheel = resolveAccessibleWheel(wheelId, callerUserId, tenantId);
        int limit = resolveLimit(rawLimit);
        return wheelDrawRepository.findByWheelIdOrderByDrawnAtDesc(wheel.getId(), PageRequest.of(0, limit)).stream()
                .map(WheelDrawResponse::from)
                .toList();
    }

    /**
     * Parses and validates the {@code limit} query parameter.
     *
     * @param rawLimit the raw query parameter value, or {@code null} if omitted
     * @return the resolved limit, defaulting to {@value #DEFAULT_DRAW_HISTORY_LIMIT}
     * @throws WheelValidationException with code {@code INVALID_LIMIT} if not a valid integer
     *     between 1 and {@value #MAX_DRAW_HISTORY_LIMIT}
     */
    private int resolveLimit(final String rawLimit) {
        if (rawLimit == null) {
            return DEFAULT_DRAW_HISTORY_LIMIT;
        }
        int limit;
        try {
            limit = Integer.parseInt(rawLimit);
        } catch (NumberFormatException ex) {
            throw new WheelValidationException("INVALID_LIMIT", "limit must be an integer: " + rawLimit);
        }
        if (limit < 1 || limit > MAX_DRAW_HISTORY_LIMIT) {
            throw new WheelValidationException(
                    "INVALID_LIMIT", "limit must be between 1 and " + MAX_DRAW_HISTORY_LIMIT + ": " + limit);
        }
        return limit;
    }

    /**
     * Resolves a wheel by id and tenant, then verifies the caller is a member of its team — same
     * anti-enumeration convention as {@code WheelService} (US14.1.1): a wheel that does not
     * exist, belongs to another tenant, or is not accessible to the caller's team all resolve to
     * the same {@link WheelNotFoundException}, never a 403.
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
}
