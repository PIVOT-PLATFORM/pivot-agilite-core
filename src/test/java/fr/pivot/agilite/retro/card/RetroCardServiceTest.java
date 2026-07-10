package fr.pivot.agilite.retro.card;

import fr.pivot.agilite.retro.card.dto.CardAddedFacilitatorEvent;
import fr.pivot.agilite.retro.card.dto.CardAddedMaskedEvent;
import fr.pivot.agilite.retro.card.dto.SubmitCardRequest;
import fr.pivot.agilite.retro.session.RetroPhase;
import fr.pivot.agilite.retro.session.RetroSession;
import fr.pivot.agilite.retro.session.RetroSessionRepository;
import fr.pivot.agilite.retro.ws.RetroAccessGrantService;
import fr.pivot.agilite.retro.ws.RetroParticipantGrant;
import fr.pivot.agilite.retro.ws.RetroSessionDestinations;
import fr.pivot.agilite.ws.WsErrorPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RetroCardService} (US20.1.2a) — masking, anonymity resolution, phase
 * gating, and error-notification behavior, all against mocked collaborators.
 *
 * <p>Full round-trip proof of the "never visible in clear before {@code CARDS_REVEALED}" AC
 * against a real STOMP transport is covered separately by {@code RetroCardSubmissionIT}.
 */
class RetroCardServiceTest {

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final String ACCESS_TOKEN = "token-1";
    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    private RetroCardRepository cardRepository;
    private RetroSessionRepository sessionRepository;
    private RetroAccessGrantService grantService;
    private SimpMessagingTemplate messagingTemplate;
    private Principal principal;
    private RetroCardService service;

    @BeforeEach
    void setUp() {
        cardRepository = mock(RetroCardRepository.class);
        sessionRepository = mock(RetroSessionRepository.class);
        grantService = mock(RetroAccessGrantService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        principal = () -> "connection-1";
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new RetroCardService(cardRepository, sessionRepository, grantService, messagingTemplate, clock);

        when(cardRepository.save(any(RetroCard.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * Given an authenticated, non-anonymous participant and a session in CONTRIBUTION, when a
     * card is submitted, then it is persisted with the resolved author, and both the masked
     * (all-participants) and unmasked (facilitator-only) events are broadcast.
     */
    @Test
    void submit_authenticatedNonAnonymous_persistsWithAuthorAndBroadcastsBoth() {
        when(grantService.resolveGrant(SESSION_ID, ACCESS_TOKEN))
                .thenReturn(Optional.of(new RetroParticipantGrant(42L, 7L, false)));
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(contributionSession()));
        when(cardRepository.countBySessionIdAndColumnKey(SESSION_ID, "went-well")).thenReturn(3L);

        service.submit(SESSION_ID, new SubmitCardRequest("Great sprint", "went-well", false), ACCESS_TOKEN, principal);

        ArgumentCaptor<RetroCard> cardCaptor = ArgumentCaptor.forClass(RetroCard.class);
        verify(cardRepository).save(cardCaptor.capture());
        RetroCard saved = cardCaptor.getValue();
        assertThat(saved.getAuthorUserId()).isEqualTo(42L);
        assertThat(saved.isAnonymous()).isFalse();
        assertThat(saved.getContent()).isEqualTo("Great sprint");
        assertThat(saved.getColumnKey()).isEqualTo("went-well");

        ArgumentCaptor<Object> maskedCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq(RetroSessionDestinations.roomTopic(SESSION_ID)), maskedCaptor.capture());
        CardAddedMaskedEvent masked = (CardAddedMaskedEvent) maskedCaptor.getValue();
        assertThat(masked.cardCount()).isEqualTo(3L);
        assertThat(masked.columnKey()).isEqualTo("went-well");

        ArgumentCaptor<Object> facilitatorCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(
                eq(RetroSessionDestinations.facilitatorTopic(SESSION_ID)), facilitatorCaptor.capture());
        CardAddedFacilitatorEvent facilitatorEvent = (CardAddedFacilitatorEvent) facilitatorCaptor.getValue();
        assertThat(facilitatorEvent.content()).isEqualTo("Great sprint");
        assertThat(facilitatorEvent.anonymous()).isFalse();
    }

    /**
     * Security AC: given the masked event broadcast to the regular session topic, then it never
     * carries the card's content or id — only the column and count.
     */
    @Test
    void submit_maskedEventNeverCarriesContent() {
        when(grantService.resolveGrant(SESSION_ID, ACCESS_TOKEN))
                .thenReturn(Optional.of(new RetroParticipantGrant(42L, 7L, false)));
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(contributionSession()));
        when(cardRepository.countBySessionIdAndColumnKey(SESSION_ID, "went-well")).thenReturn(1L);

        service.submit(SESSION_ID, new SubmitCardRequest("Secret content", "went-well", false), ACCESS_TOKEN, principal);

        ArgumentCaptor<Object> maskedCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq(RetroSessionDestinations.roomTopic(SESSION_ID)), maskedCaptor.capture());
        assertThat(maskedCaptor.getValue().toString()).doesNotContain("Secret content");
    }

    /**
     * Given a participant explicitly requesting anonymous submission, when the card is
     * submitted, then it is persisted with {@code anonymous = true} and no author, regardless of
     * the participant being otherwise authenticated.
     */
    @Test
    void submit_explicitlyAnonymous_persistsWithoutAuthorEvenIfAuthenticated() {
        when(grantService.resolveGrant(SESSION_ID, ACCESS_TOKEN))
                .thenReturn(Optional.of(new RetroParticipantGrant(42L, 7L, false)));
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(contributionSession()));
        when(cardRepository.countBySessionIdAndColumnKey(SESSION_ID, "to-improve")).thenReturn(1L);

        service.submit(SESSION_ID, new SubmitCardRequest("Anonymous gripe", "to-improve", true), ACCESS_TOKEN, principal);

        ArgumentCaptor<RetroCard> cardCaptor = ArgumentCaptor.forClass(RetroCard.class);
        verify(cardRepository).save(cardCaptor.capture());
        assertThat(cardCaptor.getValue().isAnonymous()).isTrue();
        assertThat(cardCaptor.getValue().getAuthorUserId()).isNull();
    }

    /**
     * Given an anonymous, account-less participant (no resolvable userId), when a card is
     * submitted without explicitly requesting anonymity, then it is still persisted without an
     * author — there is simply no identity to attach.
     */
    @Test
    void submit_accountLessParticipant_persistsWithoutAuthor() {
        when(grantService.resolveGrant(SESSION_ID, ACCESS_TOKEN))
                .thenReturn(Optional.of(RetroParticipantGrant.anonymous()));
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(contributionSession()));
        when(cardRepository.countBySessionIdAndColumnKey(SESSION_ID, "went-well")).thenReturn(1L);

        service.submit(SESSION_ID, new SubmitCardRequest("Guest feedback", "went-well", false), ACCESS_TOKEN, principal);

        ArgumentCaptor<RetroCard> cardCaptor = ArgumentCaptor.forClass(RetroCard.class);
        verify(cardRepository).save(cardCaptor.capture());
        assertThat(cardCaptor.getValue().isAnonymous()).isTrue();
        assertThat(cardCaptor.getValue().getAuthorUserId()).isNull();
    }

    /**
     * Given a session no longer in CONTRIBUTION phase, when a card is submitted, then it is
     * rejected: nothing is persisted or broadcast, and the sender alone is notified.
     */
    @Test
    void submit_sessionNotInContributionPhase_rejectsAndNotifiesSenderOnly() {
        when(grantService.resolveGrant(SESSION_ID, ACCESS_TOKEN))
                .thenReturn(Optional.of(new RetroParticipantGrant(42L, 7L, false)));
        RetroSession session = contributionSession();
        session.setCurrentPhase(RetroPhase.REVUE);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        service.submit(SESSION_ID, new SubmitCardRequest("Too late", "went-well", false), ACCESS_TOKEN, principal);

        verify(cardRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verify(messagingTemplate).convertAndSendToUser(eq("connection-1"), eq("/queue/errors"), any(WsErrorPayload.class));
    }

    /**
     * Given blank card content, when submitted, then it is rejected without persisting or
     * broadcasting, and the sender is notified.
     */
    @Test
    void submit_blankContent_rejectsAndNotifiesSender() {
        when(grantService.resolveGrant(SESSION_ID, ACCESS_TOKEN))
                .thenReturn(Optional.of(new RetroParticipantGrant(42L, 7L, false)));
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(contributionSession()));

        service.submit(SESSION_ID, new SubmitCardRequest("   ", "went-well", false), ACCESS_TOKEN, principal);

        verify(cardRepository, never()).save(any());
        verify(messagingTemplate).convertAndSendToUser(eq("connection-1"), eq("/queue/errors"), any(WsErrorPayload.class));
    }

    /**
     * Given a blank column key, when submitted, then it is rejected without persisting or
     * broadcasting, and the sender is notified.
     */
    @Test
    void submit_blankColumnKey_rejectsAndNotifiesSender() {
        when(grantService.resolveGrant(SESSION_ID, ACCESS_TOKEN))
                .thenReturn(Optional.of(new RetroParticipantGrant(42L, 7L, false)));
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(contributionSession()));

        service.submit(SESSION_ID, new SubmitCardRequest("content", " ", false), ACCESS_TOKEN, principal);

        verify(cardRepository, never()).save(any());
        verify(messagingTemplate).convertAndSendToUser(eq("connection-1"), eq("/queue/errors"), any(WsErrorPayload.class));
    }

    /**
     * Given an unknown session, when a card is submitted, then it is rejected without persisting
     * or broadcasting, and the sender is notified.
     */
    @Test
    void submit_unknownSession_rejectsAndNotifiesSender() {
        when(grantService.resolveGrant(SESSION_ID, ACCESS_TOKEN))
                .thenReturn(Optional.of(new RetroParticipantGrant(42L, 7L, false)));
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        service.submit(SESSION_ID, new SubmitCardRequest("content", "went-well", false), ACCESS_TOKEN, principal);

        verify(cardRepository, never()).save(any());
        verify(messagingTemplate).convertAndSendToUser(eq("connection-1"), eq("/queue/errors"), any(WsErrorPayload.class));
    }

    /**
     * Defensive-only: given no valid grant resolves at all (should never happen — the channel
     * interceptor already denies such SEND frames upstream), when a card is submitted, then it
     * is rejected without persisting or broadcasting.
     */
    @Test
    void submit_noGrant_rejectsDefensively() {
        when(grantService.resolveGrant(SESSION_ID, ACCESS_TOKEN)).thenReturn(Optional.empty());

        service.submit(SESSION_ID, new SubmitCardRequest("content", "went-well", false), ACCESS_TOKEN, principal);

        verify(cardRepository, never()).save(any());
        verify(sessionRepository, never()).findById(any());
        verify(messagingTemplate, times(1))
                .convertAndSendToUser(eq("connection-1"), eq("/queue/errors"), any(WsErrorPayload.class));
    }

    /**
     * Given a {@code null} principal (should not normally occur), when an error path is
     * triggered, then no notification attempt is made (no NPE either).
     */
    @Test
    void submit_nullPrincipal_doesNotThrow() {
        when(grantService.resolveGrant(SESSION_ID, ACCESS_TOKEN)).thenReturn(Optional.empty());

        service.submit(SESSION_ID, new SubmitCardRequest("content", "went-well", false), ACCESS_TOKEN, null);

        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    private static RetroSession contributionSession() {
        return new RetroSession(
                7L, 1L, "Sprint Retro", fr.pivot.agilite.retro.session.RetroFormat.START_STOP_CONTINUE,
                null, null, 99L, "ABC123", null, null, null, 3,
                NOW.plusSeconds(3600), NOW);
    }
}
