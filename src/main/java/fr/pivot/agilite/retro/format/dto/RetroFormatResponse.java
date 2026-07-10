package fr.pivot.agilite.retro.format.dto;

import fr.pivot.agilite.retro.format.RetroCustomFormat;
import fr.pivot.agilite.retro.format.RetroFormatCatalog;
import fr.pivot.agilite.retro.format.RetroFormatColumn;
import fr.pivot.agilite.retro.session.RetroFormat;

import java.util.List;
import java.util.Objects;

/**
 * A single entry of the retrospective format catalogue — one of the 4 predefined system formats,
 * or one of the calling tenant's custom formats (US20.2.1).
 *
 * <p>Returned by {@code GET /retro/formats} (list, system formats first) and {@code POST
 * /retro/formats} (the single newly created custom format, HTTP 201).
 *
 * @param key     the {@link RetroFormat} name for system formats, or the format's generated UUID
 *                (as a string) for custom ones
 * @param label   human-readable format label
 * @param system  {@code true} for one of the 4 immutable predefined formats, {@code false} for a
 *                tenant-owned custom format
 * @param columns the format's columns, in display order
 */
public record RetroFormatResponse(
        String key,
        String label,
        boolean system,
        List<RetroFormatColumnResponse> columns) {

    /**
     * Builds the response for one of the 4 predefined system formats.
     *
     * @param format the system format
     * @return a populated response record with {@code system = true}
     */
    public static RetroFormatResponse forSystemFormat(final RetroFormat format) {
        return new RetroFormatResponse(
                format.name(), RetroFormatCatalog.labelOf(format), true, RetroFormatCatalog.columnsOf(format));
    }

    /**
     * Builds the response for a tenant-owned custom format entity.
     *
     * @param format the persisted custom format — must already have an id (i.e. have been
     *               through a real {@code save()}); both current call sites (post-creation,
     *               post-repository-read) guarantee this
     * @return a populated response record with {@code system = false}
     * @throws NullPointerException if {@code format} has not yet been persisted (no id assigned)
     *     — a deliberately loud failure rather than silently returning a null/garbage key
     */
    public static RetroFormatResponse from(final RetroCustomFormat format) {
        Objects.requireNonNull(format.getId(), "format.getId() — cannot build a response for an unsaved format");
        List<RetroFormatColumnResponse> columns = format.getColumns().stream()
                .map(RetroFormatResponse::toColumnResponse)
                .toList();
        return new RetroFormatResponse(format.getId().toString(), format.getLabel(), false, columns);
    }

    private static RetroFormatColumnResponse toColumnResponse(final RetroFormatColumn column) {
        return new RetroFormatColumnResponse(
                column.getKey(), column.getLabel(), column.getColor(), column.getDescription(), column.getIcon());
    }
}
