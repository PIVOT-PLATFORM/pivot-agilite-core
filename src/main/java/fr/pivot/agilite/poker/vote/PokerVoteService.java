package fr.pivot.agilite.poker.vote;

import fr.pivot.agilite.poker.PokerCardDeck;
import fr.pivot.agilite.poker.ticket.PokerTicket;
import fr.pivot.agilite.poker.ticket.PokerTicketRepository;
import fr.pivot.agilite.poker.ticket.PokerTicketStatus;
import fr.pivot.agilite.poker.vote.dto.SubmitVoteRequest;
import fr.pivot.agilite.poker.vote.dto.VoteCastEvent;
import fr.pivot.agilite.poker.ws.PokerParticipantRegistryService;
import fr.pivot.agilite.poker.ws.PokerRoomDestinations;
import fr.pivot.agilite.ws.WsErrorPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for planning poker vote submission (US09.2.1).
 *
 * <p>Invoked from {@code PokerVoteWsController}'s {@code @MessageMapping} handler — every
 * rejection path here (unknown/cross-room ticket, revealed ticket, invalid card value) notifies
 * the sender alone via {@code /user/queue/errors}, never a broadcast, mirroring {@code
 * RetroCardService}'s established STOMP error-handling convention (US20.1.2a).
 *
 * <p><strong>Voter identity, never a client-supplied {@code userId}.</strong> The voter's
 * identity for the purpose of "one vote per participant, upsertable" is derived exclusively from
 * the room access token already verified by {@code PokerChannelInterceptor} (EN09.1) before this
 * handler ever runs — specifically, a SHA-256 hex digest of that token, computed here and never
 * the raw token itself. Hashing (rather than storing the raw token) is a defense-in-depth choice:
 * {@code agilite.poker_votes} rows persist indefinitely (tied to the ticket's lifetime, not the
 * token's Redis TTL), so a future leak of that table alone must never hand out a still-usable
 * access token.
 */
@Service
public class PokerVoteService {

    private static final Logger LOG = LoggerFactory.getLogger(PokerVoteService.class);

    private static final String HASH_ALGORITHM = "SHA-256";

    private final PokerVoteRepository voteRepository;
    private final PokerTicketRepository ticketRepository;
    private final PokerParticipantRegistryService participantRegistryService;
    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    /**
     * Constructs the service with its required dependencies.
     *
     * @param voteRepository             vote persistence
     * @param ticketRepository           ticket persistence, used to resolve the target ticket and
     *                                   check its room/status
     * @param participantRegistryService resolves the room's total registered participant count
     * @param messagingTemplate          used to broadcast {@code VOTE_CAST} events and error
     *                                   notifications
     * @param clock                      the shared clock, overridable in tests
     */
    public PokerVoteService(
            final PokerVoteRepository voteRepository,
            final PokerTicketRepository ticketRepository,
            final PokerParticipantRegistryService participantRegistryService,
            final SimpMessagingTemplate messagingTemplate,
            final Clock clock) {
        this.voteRepository = voteRepository;
        this.ticketRepository = ticketRepository;
        this.participantRegistryService = participantRegistryService;
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
    }

    /**
     * Submits (or changes) a vote, persists it, and broadcasts the masked {@code VOTE_CAST}
     * event to every participant of the room — never the chosen value.
     *
     * @param roomId      the room, from the STOMP destination
     * @param request     the ticket id and chosen card value
     * @param accessToken the caller's access token, used both for identity (hashed) and to
     *                    re-derive the participant roster count
     * @param principal   the caller's connection principal, used to address error notifications
     */
    @Transactional
    public void submit(
            final UUID roomId, final SubmitVoteRequest request,
            final String accessToken, final Principal principal) {
        if (request.ticketId() == null) {
            notifyError(principal, "Ticket not found");
            return;
        }
        Optional<PokerTicket> ticketOpt = ticketRepository.findById(request.ticketId());
        if (ticketOpt.isEmpty() || !ticketOpt.get().getRoomId().equals(roomId)) {
            // Collapsed on purpose: an unknown ticket and a ticket belonging to a different room
            // are indistinguishable to the caller — never confirms cross-room existence.
            notifyError(principal, "Ticket not found");
            return;
        }
        PokerTicket ticket = ticketOpt.get();
        if (ticket.getStatus() != PokerTicketStatus.VOTING) {
            notifyError(principal, "Voting is closed for this ticket");
            return;
        }
        if (!PokerCardDeck.FIBONACCI_VALUES.contains(request.value())) {
            notifyError(principal, "Invalid card value");
            return;
        }

        String participantKey = hash(accessToken);
        var now = clock.instant();
        voteRepository.findByTicketIdAndParticipantKey(ticket.getId(), participantKey)
                .ifPresentOrElse(
                        existing -> existing.changeValue(request.value(), now),
                        () -> voteRepository.save(
                                new PokerVote(ticket.getId(), participantKey, request.value(), now)));

        long votedCount = voteRepository.countByTicketId(ticket.getId());
        long totalParticipants = participantRegistryService.countActive(roomId);
        LOG.info("Vote recorded: room={} ticket={} votedCount={}", roomId, ticket.getId(), votedCount);

        messagingTemplate.convertAndSend(
                PokerRoomDestinations.roomTopic(roomId),
                (Object) VoteCastEvent.of(roomId, ticket.getId(), votedCount, totalParticipants));
    }

    /**
     * Sends an error notification to the sender's {@code /user/queue/errors} destination.
     *
     * @param principal the sender, or {@code null} (in which case nothing is sent)
     * @param error     the human-readable error reason
     */
    private void notifyError(final Principal principal, final String error) {
        if (principal == null) {
            return;
        }
        try {
            messagingTemplate.convertAndSendToUser(principal.getName(), "/queue/errors", new WsErrorPayload(error));
        } catch (Exception e) {
            LOG.debug("Could not deliver error notification: {}", e.getMessage());
        }
    }

    /**
     * Computes the SHA-256 hex digest of an access token — the persisted voter identity, never
     * the raw token itself.
     *
     * @param accessToken the raw access token to hash
     * @return the lowercase hex-encoded digest
     */
    private static String hash(final String accessToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashed = digest.digest(accessToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a standard JDK algorithm guaranteed available on every supported
            // platform (JLS-mandated) — this can never actually happen at runtime.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
