package fr.pivot.agilite.wheel.dto;

import fr.pivot.agilite.wheel.WheelEntry;

import java.util.UUID;

/**
 * Response payload for a single wheel entry (US14.1.1).
 *
 * @param id           unique identifier of the entry
 * @param type         entry kind, lowercase ({@code "team_member"} or {@code "free_text"})
 * @param teamMemberId the referenced {@code public.team_members.id}, or {@code null}
 * @param label        display label
 * @param weight       draw weight, 1-10
 */
public record WheelEntryResponse(
        UUID id,
        String type,
        Long teamMemberId,
        String label,
        int weight) {

    /**
     * Factory method that creates a {@link WheelEntryResponse} from a {@link WheelEntry} entity.
     *
     * @param entry the entry entity
     * @return a populated response record
     */
    public static WheelEntryResponse from(final WheelEntry entry) {
        return new WheelEntryResponse(
                entry.getId(),
                entry.getEntryType().name().toLowerCase(),
                entry.getTeamMemberId(),
                entry.getLabel(),
                entry.getWeight());
    }
}
