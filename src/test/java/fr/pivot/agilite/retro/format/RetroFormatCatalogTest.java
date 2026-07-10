package fr.pivot.agilite.retro.format;

import fr.pivot.agilite.retro.format.dto.RetroFormatColumnResponse;
import fr.pivot.agilite.retro.session.RetroFormat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Unit tests for {@link RetroFormatCatalog}, pinning the exact column shape of the 4 system
 * formats against the Gate-1 AC table (US20.2.1) — a regression here would silently change the
 * wire contract every tenant's frontend renders.
 */
class RetroFormatCatalogTest {

    /**
     * Given the catalogue, when systemFormats() is called, then it returns exactly the 4 system
     * formats, in the fixed display order, never including {@link RetroFormat#CUSTOM}.
     */
    @Test
    void systemFormats_returnsExactlyTheFourInFixedOrder() {
        assertThat(RetroFormatCatalog.systemFormats()).containsExactly(
                RetroFormat.START_STOP_CONTINUE,
                RetroFormat.KIF_KAF,
                RetroFormat.FOUR_L,
                RetroFormat.MAD_SAD_GLAD);
    }

    /**
     * Given each system format, when labelOf() is called, then it returns the exact format-level
     * label from the AC table.
     */
    @Test
    void labelOf_returnsExactFormatLevelLabels() {
        assertThat(RetroFormatCatalog.labelOf(RetroFormat.START_STOP_CONTINUE))
                .isEqualTo("Start / Stop / Continue");
        assertThat(RetroFormatCatalog.labelOf(RetroFormat.KIF_KAF)).isEqualTo("Kif / Kaf");
        assertThat(RetroFormatCatalog.labelOf(RetroFormat.FOUR_L)).isEqualTo("4L");
        assertThat(RetroFormatCatalog.labelOf(RetroFormat.MAD_SAD_GLAD)).isEqualTo("Mad / Sad / Glad");
    }

    /**
     * Given {@link RetroFormat#START_STOP_CONTINUE}, when columnsOf() is called, then it returns
     * exactly its 3 columns, in order, with the exact key/label/color/icon/description.
     */
    @Test
    void columnsOf_startStopContinue_returnsExactColumns() {
        List<RetroFormatColumnResponse> columns = RetroFormatCatalog.columnsOf(RetroFormat.START_STOP_CONTINUE);

        assertThat(columns).extracting(
                        RetroFormatColumnResponse::key,
                        RetroFormatColumnResponse::label,
                        RetroFormatColumnResponse::color,
                        RetroFormatColumnResponse::icon)
                .containsExactly(
                        tuple("START", "Commencer", "#2E7D32", "play_arrow"),
                        tuple("STOP", "Arrêter", "#C62828", "stop"),
                        tuple("CONTINUE", "Continuer", "#1565C0", "autorenew"));
        assertThat(columns).extracting(RetroFormatColumnResponse::description)
                .containsExactly(
                        "Ce que l'équipe doit commencer à faire",
                        "Ce que l'équipe doit arrêter de faire",
                        "Ce que l'équipe doit continuer à faire");
    }

    /**
     * Given {@link RetroFormat#KIF_KAF}, when columnsOf() is called, then it returns exactly its
     * 2 columns, in order.
     */
    @Test
    void columnsOf_kifKaf_returnsExactColumns() {
        List<RetroFormatColumnResponse> columns = RetroFormatCatalog.columnsOf(RetroFormat.KIF_KAF);

        assertThat(columns).extracting(
                        RetroFormatColumnResponse::key,
                        RetroFormatColumnResponse::label,
                        RetroFormatColumnResponse::color,
                        RetroFormatColumnResponse::icon)
                .containsExactly(
                        tuple("KIF", "Kept It Famous", "#2E7D32", "star"),
                        tuple("KAF", "Killed A Feature", "#C62828", "heart_broken"));
    }

    /**
     * Given {@link RetroFormat#FOUR_L}, when columnsOf() is called, then it returns exactly its
     * 4 columns, in order.
     */
    @Test
    void columnsOf_fourL_returnsExactColumns() {
        List<RetroFormatColumnResponse> columns = RetroFormatCatalog.columnsOf(RetroFormat.FOUR_L);

        assertThat(columns).extracting(
                        RetroFormatColumnResponse::key,
                        RetroFormatColumnResponse::label,
                        RetroFormatColumnResponse::color,
                        RetroFormatColumnResponse::icon)
                .containsExactly(
                        tuple("LIKED", "Liked", "#2E7D32", "thumb_up"),
                        tuple("LEARNED", "Learned", "#1565C0", "school"),
                        tuple("LACKED", "Lacked", "#EF6C00", "remove_circle_outline"),
                        tuple("LONGED_FOR", "Longed For", "#6A1B9A", "auto_awesome"));
    }

    /**
     * Given {@link RetroFormat#MAD_SAD_GLAD}, when columnsOf() is called, then it returns
     * exactly its 3 columns, in order.
     */
    @Test
    void columnsOf_madSadGlad_returnsExactColumns() {
        List<RetroFormatColumnResponse> columns = RetroFormatCatalog.columnsOf(RetroFormat.MAD_SAD_GLAD);

        assertThat(columns).extracting(
                        RetroFormatColumnResponse::key,
                        RetroFormatColumnResponse::label,
                        RetroFormatColumnResponse::color,
                        RetroFormatColumnResponse::icon)
                .containsExactly(
                        tuple("MAD", "Frustrant", "#C62828", "sentiment_very_dissatisfied"),
                        tuple("SAD", "Décevant", "#EF6C00", "sentiment_dissatisfied"),
                        tuple("GLAD", "Satisfaisant", "#2E7D32", "sentiment_satisfied"));
    }

    /**
     * Given {@link RetroFormat#START_STOP_CONTINUE}, when columnKeysOf() is called, then it
     * returns only the keys, in the same order as columnsOf().
     */
    @Test
    void columnKeysOf_returnsKeysOnlyInOrder() {
        assertThat(RetroFormatCatalog.columnKeysOf(RetroFormat.START_STOP_CONTINUE))
                .containsExactly("START", "STOP", "CONTINUE");
    }

    /**
     * Given {@link RetroFormat#CUSTOM}, when columnsOf()/labelOf() is called, then it throws
     * {@link IllegalArgumentException} — CUSTOM has no static catalogue entry, its columns are
     * always tenant-defined data.
     */
    @Test
    void columnsOf_custom_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> RetroFormatCatalog.columnsOf(RetroFormat.CUSTOM))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetroFormatCatalog.labelOf(RetroFormat.CUSTOM))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
