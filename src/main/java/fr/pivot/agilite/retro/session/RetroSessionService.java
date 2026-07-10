package fr.pivot.agilite.retro.session;

import fr.pivot.agilite.exception.InvalidRetroFormatException;
import fr.pivot.agilite.exception.RetroCustomFormatIdNotAllowedException;
import fr.pivot.agilite.exception.RetroCustomFormatIdRequiredException;
import fr.pivot.agilite.exception.RetroCustomFormatNotFoundException;
import fr.pivot.agilite.exception.RetroJoinCodeNotFoundException;
import fr.pivot.agilite.exception.RetroSessionExpiredException;
import fr.pivot.agilite.exception.RetroSessionNotFoundException;
import fr.pivot.agilite.exception.RetroTeamAccessDeniedException;
import fr.pivot.agilite.exception.RetroTeamNotFoundException;
import fr.pivot.agilite.retro.format.RetroCustomFormatRepository;
import fr.pivot.agilite.retro.session.dto.CreateRetroSessionRequest;
import fr.pivot.agilite.retro.session.dto.RetroSessionJoinResponse;
import fr.pivot.agilite.retro.session.dto.RetroSessionResponse;
import fr.pivot.core.team.Team;
import fr.pivot.core.team.TeamMemberRepository;
import fr.pivot.core.team.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Business logic for creating and reading retrospective sessions (US20.1.1).
 *
 * <p>Team existence/tenant-ownership/caller-membership are validated directly against {@code
 * fr.pivot.core.team.TeamRepository}/{@code TeamMemberRepository}, exported as-is by {@code
 * pivot-core-starter} — no local duplication of the {@code Team}/{@code TeamMember} entities.
 */
@Service
public class RetroSessionService {

    /** Fixed session lifetime from creation — matches the US09.1.1 scrum-poker room convention. */
    static final Duration SESSION_TTL = Duration.ofHours(24);

    /** Default number of dot-votes per participant when the caller does not specify one. */
    static final int DEFAULT_VOTE_COUNT_PER_PARTICIPANT = 3;

    private final RetroSessionRepository sessionRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final JoinCodeGenerator joinCodeGenerator;
    private final RetroCustomFormatRepository customFormatRepository;

    /**
     * Constructs the service with its required dependencies.
     *
     * @param sessionRepository      retro session persistence
     * @param teamRepository         {@code pivot-core-starter}'s team persistence
     * @param teamMemberRepository   {@code pivot-core-starter}'s team membership persistence
     * @param joinCodeGenerator      unique join code generator
     * @param customFormatRepository tenant-owned custom format persistence (US20.2.1), used to
     *                               resolve {@code customFormatId} on creation
     */
    public RetroSessionService(
            final RetroSessionRepository sessionRepository,
            final TeamRepository teamRepository,
            final TeamMemberRepository teamMemberRepository,
            final JoinCodeGenerator joinCodeGenerator,
            final RetroCustomFormatRepository customFormatRepository) {
        this.sessionRepository = sessionRepository;
        this.teamRepository = teamRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.joinCodeGenerator = joinCodeGenerator;
        this.customFormatRepository = customFormatRepository;
    }

    /**
     * Creates a new retro session. The caller becomes the facilitator.
     *
     * @param request  the validated creation request
     * @param callerId the authenticated caller's {@code public.users.id}
     * @param tenantId the authenticated caller's {@code public.tenants.id}, extracted exclusively
     *                 from the resolved bearer token — never from the request body
     * @return the created session, fully detailed
     * @throws RetroTeamNotFoundException           if {@code teamId} does not exist, or belongs
     *                                               to a different tenant (never disclosed
     *                                               which, both collapse to 404)
     * @throws RetroTeamAccessDeniedException       if the team exists in the caller's tenant but
     *                                               the caller is not one of its members
     * @throws InvalidRetroFormatException          if {@code format} does not match any {@link
     *                                               RetroFormat} constant
     * @throws RetroCustomFormatIdRequiredException if {@code format} is {@code CUSTOM} and no
     *                                               {@code customFormatId} was supplied
     *                                               (US20.2.1)
     * @throws RetroCustomFormatNotFoundException   if {@code format} is {@code CUSTOM} and {@code
     *                                               customFormatId} does not resolve to a custom
     *                                               format owned by the caller's tenant
     *                                               (US20.2.1, never 403)
     * @throws RetroCustomFormatIdNotAllowedException if {@code format} is not {@code CUSTOM} but
     *                                               a {@code customFormatId} was supplied anyway
     *                                               (US20.2.1)
     */
    @Transactional
    public RetroSessionResponse create(
            final CreateRetroSessionRequest request, final Long callerId, final Long tenantId) {
        Team team = teamRepository.findById(request.teamId())
                .filter(candidate -> candidate.getTenantId().equals(tenantId))
                .orElseThrow(() -> new RetroTeamNotFoundException(request.teamId()));

        teamMemberRepository.findByTeamIdAndUserId(team.getId(), callerId)
                .orElseThrow(() -> new RetroTeamAccessDeniedException(team.getId()));

        RetroFormat format = parseFormat(request.format());
        validateCustomFormatId(format, request.customFormatId(), tenantId);
        String joinCode = joinCodeGenerator.generate();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(SESSION_TTL);
        Integer voteCount = request.voteCountPerParticipant() != null
                ? request.voteCountPerParticipant()
                : DEFAULT_VOTE_COUNT_PER_PARTICIPANT;

        RetroSession session = new RetroSession(
                tenantId,
                team.getId(),
                request.title(),
                format,
                request.customFormatId(),
                request.sprintRef(),
                callerId,
                joinCode,
                request.contributionTimerSeconds(),
                request.voteTimerSeconds(),
                request.actionTimerSeconds(),
                voteCount,
                expiresAt,
                now);

        return RetroSessionResponse.from(sessionRepository.save(session));
    }

    /**
     * Returns the full detail of a session for an authenticated, tenant-matching caller —
     * regardless of the session's current phase, including {@link RetroPhase#CLOSED} (US20.1.2c:
     * a closed session stays readable). Only tenant membership is checked here, not team
     * membership — any authenticated member of the owning tenant may read a session's detail.
     *
     * @param id       the session UUID from the path
     * @param tenantId the authenticated caller's tenant id
     * @return the full session detail
     * @throws RetroSessionNotFoundException if the session does not exist, or belongs to a
     *                                        different tenant (both collapse to 404)
     */
    @Transactional(readOnly = true)
    public RetroSessionResponse findByIdForTenant(final UUID id, final Long tenantId) {
        RetroSession session = sessionRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new RetroSessionNotFoundException(id));
        return RetroSessionResponse.from(session);
    }

    /**
     * Resolves a join code to the minimal public metadata needed by an unauthenticated
     * participant, for a session that is still joinable.
     *
     * @param joinCode the 6-character alphanumeric join code
     * @return the minimal join metadata
     * @throws RetroJoinCodeNotFoundException if the join code is unknown
     * @throws RetroSessionExpiredException   if the session has expired or is already closed —
     *                                         this gate applies only here, never to {@link
     *                                         #findByIdForTenant(UUID, Long)}
     */
    @Transactional(readOnly = true)
    public RetroSessionJoinResponse findByJoinCode(final String joinCode) {
        RetroSession session = sessionRepository.findByJoinCode(joinCode)
                .orElseThrow(RetroJoinCodeNotFoundException::new);

        if (session.getCurrentPhase() == RetroPhase.CLOSED) {
            throw new RetroSessionExpiredException("Retro session is closed");
        }
        if (Instant.now().isAfter(session.getExpiresAt())) {
            throw new RetroSessionExpiredException("Retro session has expired");
        }

        return RetroSessionJoinResponse.from(session);
    }

    /**
     * Validates a raw format string against the {@link RetroFormat} catalogue.
     *
     * @param rawFormat the raw format string from the request
     * @return the matching {@link RetroFormat} constant
     * @throws InvalidRetroFormatException if {@code rawFormat} does not match any constant
     */
    private static RetroFormat parseFormat(final String rawFormat) {
        try {
            return RetroFormat.valueOf(rawFormat);
        } catch (IllegalArgumentException ex) {
            throw new InvalidRetroFormatException(rawFormat);
        }
    }

    /**
     * Validates the cross-field {@code format}/{@code customFormatId} rule (US20.2.1): required
     * and tenant-resolved when {@code format == CUSTOM}, forbidden otherwise.
     *
     * @param format         the already-parsed format
     * @param customFormatId the raw {@code customFormatId} from the request, may be {@code null}
     * @param tenantId       the caller's tenant id, used to scope the custom-format lookup
     * @throws RetroCustomFormatIdRequiredException   if {@code format == CUSTOM} and {@code
     *                                                 customFormatId} is {@code null}
     * @throws RetroCustomFormatNotFoundException     if {@code format == CUSTOM} and {@code
     *                                                 customFormatId} does not resolve to a
     *                                                 custom format owned by {@code tenantId}
     * @throws RetroCustomFormatIdNotAllowedException if {@code format != CUSTOM} and {@code
     *                                                 customFormatId} is not {@code null}
     */
    private void validateCustomFormatId(
            final RetroFormat format, final UUID customFormatId, final Long tenantId) {
        if (format == RetroFormat.CUSTOM) {
            if (customFormatId == null) {
                throw new RetroCustomFormatIdRequiredException();
            }
            customFormatRepository.findByIdAndTenantId(customFormatId, tenantId)
                    .orElseThrow(() -> new RetroCustomFormatNotFoundException(customFormatId));
        } else if (customFormatId != null) {
            throw new RetroCustomFormatIdNotAllowedException();
        }
    }
}
