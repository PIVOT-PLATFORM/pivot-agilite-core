package fr.pivot.agilite.wheel;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Kind of a {@link WheelEntry} — either a reference to an existing team member or a free-text
 * ad hoc entrant (US14.1.1).
 *
 * <p>Serialized/deserialized as lowercase JSON values ({@code "team_member"}/{@code
 * "free_text"}) via {@link JsonProperty} — the API contract documented in the backlog AC,
 * distinct from the uppercase Java enum constant names used in the database ({@code
 * entry_type} column) and JPA mapping ({@code @Enumerated(EnumType.STRING)}).
 */
public enum WheelEntryType {

    /** Entry backed by a {@code public.team_members.id} reference — native import. */
    @JsonProperty("team_member")
    TEAM_MEMBER,

    /** Entry backed by a free-text label, for entrants without a PIVOT account. */
    @JsonProperty("free_text")
    FREE_TEXT
}
