package fr.pivot.agilite.retro.format;

import fr.pivot.agilite.retro.format.dto.RetroFormatColumnResponse;
import fr.pivot.agilite.retro.session.RetroFormat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static, immutable catalogue of the 4 predefined ("system") retrospective formats' columns
 * (US20.2.1).
 *
 * <p>Deliberately in-code data, never database rows — the structural guarantee that no request
 * of any kind (there is no {@code PUT}/{@code PATCH}/{@code DELETE} route on {@code
 * /retro/formats/{key}} at all) can ever alter a system format. Single source of truth, reused by
 * {@code GET /retro/formats} here, and intended for reuse by US20.1.2a's card-column validation
 * (out of scope for this US) via {@link #columnKeysOf(RetroFormat)}.
 *
 * <p>{@link RetroFormat#CUSTOM} is deliberately absent from this catalogue — it has no static
 * column shape, its columns are always tenant-defined data in {@link RetroCustomFormat}.
 */
public final class RetroFormatCatalog {

    private static final Map<RetroFormat, Entry> ENTRIES = buildEntries();

    private RetroFormatCatalog() {
    }

    /**
     * Returns the 4 system formats, in the fixed display order expected by {@code GET
     * /retro/formats}.
     *
     * @return the system {@link RetroFormat} constants, in catalogue order
     */
    public static List<RetroFormat> systemFormats() {
        return List.copyOf(ENTRIES.keySet());
    }

    /**
     * Returns a system format's format-level label.
     *
     * @param format the system format
     * @return the human-readable label
     * @throws IllegalArgumentException if {@code format} is not a system format (e.g. {@link
     *     RetroFormat#CUSTOM})
     */
    public static String labelOf(final RetroFormat format) {
        return entryOf(format).label();
    }

    /**
     * Returns a system format's columns, in display order.
     *
     * @param format the system format
     * @return the format's columns
     * @throws IllegalArgumentException if {@code format} is not a system format
     */
    public static List<RetroFormatColumnResponse> columnsOf(final RetroFormat format) {
        return entryOf(format).columns();
    }

    /**
     * Returns a system format's column keys only, in display order — the projection US20.1.2a's
     * card-column validation is expected to consume.
     *
     * @param format the system format
     * @return the format's column keys
     * @throws IllegalArgumentException if {@code format} is not a system format
     */
    public static List<String> columnKeysOf(final RetroFormat format) {
        return entryOf(format).columns().stream().map(RetroFormatColumnResponse::key).toList();
    }

    private static Entry entryOf(final RetroFormat format) {
        Entry entry = ENTRIES.get(format);
        if (entry == null) {
            throw new IllegalArgumentException("Not a system format: " + format);
        }
        return entry;
    }

    private static Map<RetroFormat, Entry> buildEntries() {
        Map<RetroFormat, Entry> entries = new LinkedHashMap<>();
        entries.put(RetroFormat.START_STOP_CONTINUE, new Entry("Start / Stop / Continue", List.of(
                new RetroFormatColumnResponse(
                        "START", "Commencer", "#2E7D32", "Ce que l'équipe doit commencer à faire",
                        "play_arrow"),
                new RetroFormatColumnResponse(
                        "STOP", "Arrêter", "#C62828", "Ce que l'équipe doit arrêter de faire", "stop"),
                new RetroFormatColumnResponse(
                        "CONTINUE", "Continuer", "#1565C0", "Ce que l'équipe doit continuer à faire",
                        "autorenew"))));
        entries.put(RetroFormat.KIF_KAF, new Entry("Kif / Kaf", List.of(
                new RetroFormatColumnResponse(
                        "KIF", "Kept It Famous", "#2E7D32", "Ce qui a été particulièrement réussi",
                        "star"),
                new RetroFormatColumnResponse(
                        "KAF", "Killed A Feature", "#C62828",
                        "Ce qui a posé problème ou fait perdre du temps", "heart_broken"))));
        entries.put(RetroFormat.FOUR_L, new Entry("4L", List.of(
                new RetroFormatColumnResponse(
                        "LIKED", "Liked", "#2E7D32", "Ce que l'équipe a apprécié", "thumb_up"),
                new RetroFormatColumnResponse(
                        "LEARNED", "Learned", "#1565C0", "Ce que l'équipe a appris", "school"),
                new RetroFormatColumnResponse(
                        "LACKED", "Lacked", "#EF6C00", "Ce qui a manqué à l'équipe",
                        "remove_circle_outline"),
                new RetroFormatColumnResponse(
                        "LONGED_FOR", "Longed For", "#6A1B9A",
                        "Ce que l'équipe aurait souhaité avoir", "auto_awesome"))));
        entries.put(RetroFormat.MAD_SAD_GLAD, new Entry("Mad / Sad / Glad", List.of(
                new RetroFormatColumnResponse(
                        "MAD", "Frustrant", "#C62828", "Ce qui a frustré l'équipe",
                        "sentiment_very_dissatisfied"),
                new RetroFormatColumnResponse(
                        "SAD", "Décevant", "#EF6C00", "Ce qui a déçu l'équipe",
                        "sentiment_dissatisfied"),
                new RetroFormatColumnResponse(
                        "GLAD", "Satisfaisant", "#2E7D32", "Ce qui a satisfait l'équipe",
                        "sentiment_satisfied"))));
        return entries;
    }

    /** Internal holder for a system format's label and columns. */
    private record Entry(String label, List<RetroFormatColumnResponse> columns) {
    }
}
