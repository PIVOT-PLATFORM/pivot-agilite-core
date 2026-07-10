package fr.pivot.agilite.retro.action.dto;

import fr.pivot.agilite.retro.action.RetroAction;

import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code ACTION_CREATED} event broadcast to every participant on
 * {@code /topic/agilite/retro/{sessionId}} whenever a new action is created during a session's
 * {@code ACTION} phase (US20.3.1) — same shared session topic as {@code PHASE_CHANGED}/{@code
 * CARDS_REVEALED}/{@code SESSION_CLOSED} (US20.1.2a/b/c).
 *
 * <p>Status changes ({@code PATCH /retro/actions/{actionId}}) are deliberately not broadcast —
 * this US's AC only requires real-time diffusion for creation.
 *
 * @param type        always {@code "ACTION_CREATED"} — discriminator for the shared session topic
 * @param sessionId   the session this action was created from
 * @param actionId    the persisted action's id
 * @param title       the action's title
 * @param ownerUserId the assignee's {@code public.users.id}, or {@code null}
 * @param dueDate     the due date, or {@code null}
 * @param status      the initial status name, always {@code "A_FAIRE"}
 */
public record ActionCreatedEvent(
        String type, UUID sessionId, UUID actionId, String title, Long ownerUserId,
        LocalDate dueDate, String status) {

    /** Discriminator value for this event type. */
    public static final String TYPE = "ACTION_CREATED";

    /**
     * Builds the event with {@link #TYPE} as its discriminator from a persisted action.
     *
     * @param action the persisted action
     * @return the constructed event
     */
    public static ActionCreatedEvent of(final RetroAction action) {
        return new ActionCreatedEvent(
                TYPE, action.getSessionId(), action.getId(), action.getTitle(),
                action.getOwnerUserId(), action.getDueDate(), action.getStatus().name());
    }
}
