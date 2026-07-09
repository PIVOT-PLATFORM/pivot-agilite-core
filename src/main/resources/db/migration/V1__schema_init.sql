-- pivot-agilite-core schema v1 — bootstrap only, no feature tables yet
--
-- Kept as a single consolidated file by convention until the product's first BETA —
-- see CLAUDE.md. Do not add V2+ migrations before that point; fold new DDL in here instead.

CREATE SCHEMA IF NOT EXISTS agilite;

-- US20.1.1: retro_sessions
CREATE TABLE IF NOT EXISTS agilite.retro_sessions (
    id                          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id                   BIGINT       NOT NULL REFERENCES public.tenants(id),
    team_id                     BIGINT       NOT NULL REFERENCES public.teams(id),
    title                       VARCHAR(100) NOT NULL,
    format                      VARCHAR(30)  NOT NULL,
    sprint_ref                  VARCHAR(100),
    facilitator_user_id         BIGINT       NOT NULL REFERENCES public.users(id),
    join_code                   VARCHAR(6)   NOT NULL UNIQUE,
    current_phase               VARCHAR(20)  NOT NULL DEFAULT 'CONTRIBUTION',
    contribution_timer_seconds  INTEGER,
    vote_timer_seconds          INTEGER,
    action_timer_seconds        INTEGER,
    vote_count_per_participant  INTEGER      NOT NULL DEFAULT 3,
    expires_at                  TIMESTAMPTZ  NOT NULL,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_retro_sessions_tenant_id ON agilite.retro_sessions(tenant_id);
CREATE INDEX IF NOT EXISTS idx_retro_sessions_team_id   ON agilite.retro_sessions(team_id);

-- US20.1.1 (Gate 1 anonymity design decision, pivot-docs us-creer-retro.md): schema laid down
-- now so the anonymity guarantee is enforceable from day one, not a deferred promise. An
-- anonymous card can never structurally persist an author reference (CHECK constraint below) —
-- strongest possible guarantee, chosen over encryption/restricted-access because a decryption
-- path or admin-readable column would always remain a residual leak point. Business logic (JPA
-- entity, repository, service, submission endpoint, STOMP broadcast) is US20.1.2a's scope — do
-- NOT add a Java entity/repository/controller for this table in this US; DDL only.
CREATE TABLE IF NOT EXISTS agilite.retro_cards (
    id              UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    session_id      UUID        NOT NULL REFERENCES agilite.retro_sessions(id) ON DELETE CASCADE,
    column_key      VARCHAR(50) NOT NULL,
    content         TEXT        NOT NULL,
    is_anonymous    BOOLEAN     NOT NULL DEFAULT FALSE,
    author_user_id  BIGINT      REFERENCES public.users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_retro_cards_anonymous_no_author CHECK (NOT is_anonymous OR author_user_id IS NULL)
);
CREATE INDEX IF NOT EXISTS idx_retro_cards_session_id ON agilite.retro_cards(session_id);

-- US09.1.1 — planning poker rooms. FK to public.tenants(id)/public.users(id) only — the sole
-- cross-schema references this repo's CLAUDE.md allows (never another module schema). UUID
-- primary key (not BIGSERIAL) to match agilite.retro_sessions and interop with EN09.1's
-- WebSocket isolation layer (PokerRoomDestinations/RoomAccessGrantService, both keyed on UUID).
CREATE TABLE IF NOT EXISTS agilite.poker_rooms (
    id                  UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id           BIGINT NOT NULL REFERENCES public.tenants(id),
    facilitator_user_id BIGINT NOT NULL REFERENCES public.users(id),
    name                VARCHAR(120) NOT NULL,
    invite_code         CHAR(6) NOT NULL UNIQUE,
    -- Fixed to FIBONACCI in v1 (ADR-026 §2) — the CHECK constraint enforces this at the DB
    -- layer too, not just at the API surface (no request field lets a caller override it).
    sequence            VARCHAR(20) NOT NULL DEFAULT 'FIBONACCI' CHECK (sequence = 'FIBONACCI'),
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_poker_rooms_tenant_id ON agilite.poker_rooms (tenant_id);

-- US14.1.1: wheel + wheel_entry (module La Roue)
-- FK vers public.tenants/public.users : pas de ON DELETE CASCADE (jamais supprimes en dur,
-- modele soft-delete/desactivation — meme convention que collaboratif.board).
-- team_id : ON DELETE CASCADE, meme convention que public.team_members -> public.teams
-- (pivot-core V1__schema_init.sql, fk_tm_team) : une equipe supprimee entraine ses roues.
CREATE TABLE IF NOT EXISTS agilite.wheel (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL REFERENCES public.tenants(id),
    team_id             BIGINT       NOT NULL REFERENCES public.teams(id) ON DELETE CASCADE,
    name                VARCHAR(100) NOT NULL,
    last_drawn_entry_id UUID,
    created_by          BIGINT       NOT NULL REFERENCES public.users(id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_wheel_tenant_id ON agilite.wheel(tenant_id);
CREATE INDEX IF NOT EXISTS idx_wheel_team_id   ON agilite.wheel(team_id);

-- lastDrawnEntryId: forward-looking anti-repeat marker for US14.2.1's weighted draw — always
-- NULL until that US lands, never written by anything in this migration/feature.
CREATE TABLE IF NOT EXISTS agilite.wheel_entry (
    id             UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    wheel_id       UUID         NOT NULL REFERENCES agilite.wheel(id) ON DELETE CASCADE,
    entry_type     VARCHAR(20)  NOT NULL,
    team_member_id BIGINT       REFERENCES public.team_members(id) ON DELETE SET NULL,
    label          VARCHAR(150) NOT NULL,
    weight         SMALLINT     NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_wheel_entry_type   CHECK (entry_type IN ('TEAM_MEMBER', 'FREE_TEXT')),
    CONSTRAINT chk_wheel_entry_weight CHECK (weight BETWEEN 1 AND 10)
);
CREATE INDEX IF NOT EXISTS idx_wheel_entry_wheel_id ON agilite.wheel_entry(wheel_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_wheel_entry_team_member
    ON agilite.wheel_entry(wheel_id, team_member_id) WHERE team_member_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_wheel_entry_free_text_label
    ON agilite.wheel_entry(wheel_id, lower(label)) WHERE entry_type = 'FREE_TEXT';

ALTER TABLE agilite.wheel
    ADD CONSTRAINT fk_wheel_last_drawn_entry
    FOREIGN KEY (last_drawn_entry_id) REFERENCES agilite.wheel_entry(id) ON DELETE SET NULL;
