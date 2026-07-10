package fr.pivot.agilite.retro.format.dto;

import java.util.List;

/**
 * Response wrapper for {@code GET /retro/formats} — the 4 predefined system formats (always
 * present, in a fixed order) followed by the calling tenant's own custom formats (US20.2.1).
 *
 * @param formats the ordered list of format catalogue entries
 */
public record RetroFormatListResponse(List<RetroFormatResponse> formats) {
}
