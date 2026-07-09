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
