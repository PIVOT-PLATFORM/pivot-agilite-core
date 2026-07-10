package fr.pivot.agilite.wheel;

import fr.pivot.agilite.auth.repository.PlatformTeamMemberReadRepository;
import fr.pivot.agilite.exception.WheelEmptyException;
import fr.pivot.agilite.exception.WheelNotFoundException;
import fr.pivot.agilite.exception.WheelValidationException;
import fr.pivot.agilite.wheel.dto.WheelDrawResponse;
import fr.pivot.agilite.wheel.dto.WheelSpinResponse;
import fr.pivot.agilite.wheel.ws.WheelDestinations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 *
 * <p><strong>US14.3.1 — real-time broadcast, deferred to after commit:</strong> {@link #spin}
 * broadcasts the draw result on {@code /topic/agilite/wheels/{wheelId}} only once its own
 * transaction has actually committed (see {@link #scheduleBroadcast} JavaDoc for the full
 * rationale) — deliberately stricter than {@code RetroPhaseService}/{@code RetroCardService},
 * which broadcast synchronously before their transaction commits.
 */
@Service
@Transactional(readOnly = true)
public class WheelDrawService {

    private static final Logger LOG = LoggerFactory.getLogger(WheelDrawService.class);

    /** Default number of draws returned by {@code GET .../draws} when {@code limit} is omitted. */
    static final int DEFAULT_DRAW_HISTORY_LIMIT = 20;

    /** Maximum number of draws that {@code GET .../draws} will ever return in one call. */
    static final int MAX_DRAW_HISTORY_LIMIT = 100;

    private final WheelRepository wheelRepository;
    private final PlatformTeamMemberReadRepository teamMemberRepository;
    private final WheelDrawRepository wheelDrawRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Creates the service with all required dependencies.
     *
     * @param wheelRepository      repository for wheel persistence (read + {@code
     *                             lastDrawnEntryId} update)
     * @param teamMemberRepository read-only access to {@code public.team_members}, for verifying
     *                             the caller's membership of the wheel's team
     * @param wheelDrawRepository  repository for draw-history persistence
     * @param messagingTemplate    used to broadcast the {@code SPIN_RESULT}-equivalent payload
     *                             (US14.3.1) — injected directly (not {@code @Lazy}), unlike
     *                             {@code WheelChannelInterceptor}: this class is not itself
     *                             registered during STOMP broker configuration, so no circular
     *                             dependency risk exists here (same non-lazy injection as {@code
     *                             RetroCardService}/{@code RetroPhaseService})
     */
    public WheelDrawService(
            final WheelRepository wheelRepository,
            final PlatformTeamMemberReadRepository teamMemberRepository,
            final WheelDrawRepository wheelDrawRepository,
            final SimpMessagingTemplate messagingTemplate) {
        this.wheelRepository = wheelRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.wheelDrawRepository = wheelDrawRepository;
        this.messagingTemplate = messagingTemplate;
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

        WheelSpinResponse response =
                new WheelSpinResponse(wheel.getId(), chosen.entryId(), chosen.label(), now, mode.jsonValue());
        scheduleBroadcast(response);
        return response;
    }

    /**
     * Broadcasts the draw result on {@code /topic/agilite/wheels/{wheelId}} (US14.3.1), deferred
     * until this method's enclosing transaction (always {@link #spin}, {@code @Transactional})
     * has actually committed.
     *
     * <p><strong>Why after commit, not synchronously like {@code RetroPhaseService}/{@code
     * RetroCardService}:</strong> a wheel spin is the single, consequential, non-repeatable
     * output of this whole feature — if the transaction were to fail after a synchronous
     * broadcast but before its own commit (a real, if narrow, window: a deferred JPA flush can
     * still fail constraint validation at commit time), a live audience would already have seen
     * a "winner" that, in the end, never existed in the database, with no way to retract that
     * impression. A retro phase-change or card-added event replayed after a rollback would be
     * comparatively harmless and rare. {@link TransactionSynchronizationManager#registerSynchronization}
     * with an {@code afterCommit} callback is the standard Spring mechanism for exactly this
     * guarantee.
     *
     * <p>If no Spring-managed transaction is currently active (this method invoked outside of
     * {@link #spin}'s {@code @Transactional} wrapper — the case for pure Mockito unit tests such
     * as {@code WheelDrawServiceTest}, which never open a real transaction), the broadcast fires
     * immediately as a defensive fallback, so this behavior stays directly unit-testable without
     * requiring a full Spring transactional context.
     *
     * @param response the draw result to broadcast, identical to the {@code POST /spin} HTTP
     *                 response body
     */
    private void scheduleBroadcast(final WheelSpinResponse response) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            broadcast(response);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                broadcast(response);
            }
        });
    }

    /**
     * Sends the draw result to every current subscriber of the wheel's topic. A topic with no
     * current subscribers is a documented Spring no-op — never an error, and {@code spin} must
     * never fail because nobody happened to be watching.
     *
     * @param response the draw result to broadcast
     */
    private void broadcast(final WheelSpinResponse response) {
        messagingTemplate.convertAndSend(WheelDestinations.wheelTopic(response.wheelId()), (Object) response);
        LOG.info("Wheel draw broadcast: wheel={} entry={}", response.wheelId(), response.entryId());
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
