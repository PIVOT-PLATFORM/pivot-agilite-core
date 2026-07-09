package fr.pivot.agilite.retro.session;

/**
 * Retrospective format catalogue (US20.1.1).
 *
 * <p>Only the reference value is carried by {@link RetroSession#getFormat()} in this US — the
 * detailed catalogue of columns (name, color, icon) per predefined format, and the creation of
 * a {@link #CUSTOM} format's own columns, is US20.2.1's scope, out of scope here.
 */
public enum RetroFormat {

    /** Start / Stop / Continue — three columns. */
    START_STOP_CONTINUE,

    /** Kif / Kaf ("qu'est-ce qui fait plaisir" / "qu'est-ce qui fout les capacités en l'air"). */
    KIF_KAF,

    /** 4L — Liked / Learned / Lacked / Longed for. */
    FOUR_L,

    /** Mad / Sad / Glad. */
    MAD_SAD_GLAD,

    /** Custom format, columns defined by the facilitator (US20.2.1, out of scope here). */
    CUSTOM
}
