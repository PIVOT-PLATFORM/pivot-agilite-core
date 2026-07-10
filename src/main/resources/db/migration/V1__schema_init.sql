-- pivot-agilite-core schema v1 — bootstrap only, no feature tables yet
--
-- Kept as a single consolidated file by convention until the product's first BETA —
-- see CLAUDE.md. Do not add V2+ migrations before that point; fold new DDL in here instead.

CREATE SCHEMA IF NOT EXISTS agilite;

-- US20.2.1: tenant-owned custom retro formats — the 4 system formats (RetroFormat enum, minus
-- CUSTOM) are static in-code data (RetroFormatCatalog), never rows here (structural immutability
-- guarantee: no route of any kind exists to create/alter/delete a system format). Created before
-- agilite.retro_sessions below so the latter's custom_format_id FK can reference it inline.
CREATE TABLE IF NOT EXISTS agilite.retro_formats (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL REFERENCES public.tenants(id),
    label               VARCHAR(60)  NOT NULL,
    created_by_user_id  BIGINT       NOT NULL REFERENCES public.users(id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_retro_formats_tenant_id ON agilite.retro_formats(tenant_id);

-- US20.2.1: columns of a custom format. `position` doubles as the JPA @OrderColumn Hibernate
-- uses to persist/restore display order (RetroCustomFormat#columns), and as half of the
-- composite primary key — no separate synthetic id needed since a column has no identity outside
-- its owning format. `column_key` uniqueness within a format is a DB constraint, not merely
-- app-level (RetroFormatService generates/disambiguates it, but the DB is the final guarantee).
CREATE TABLE IF NOT EXISTS agilite.retro_format_columns (
    format_id    UUID         NOT NULL REFERENCES agilite.retro_formats(id) ON DELETE CASCADE,
    position     INTEGER      NOT NULL,
    column_key   VARCHAR(50)  NOT NULL,
    label        VARCHAR(40)  NOT NULL,
    color        VARCHAR(20),
    description  VARCHAR(200),
    icon         VARCHAR(50),
    PRIMARY KEY (format_id, position),
    CONSTRAINT uq_retro_format_columns_key UNIQUE (format_id, column_key)
);

-- US20.1.1: retro_sessions
CREATE TABLE IF NOT EXISTS agilite.retro_sessions (
    id                          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id                   BIGINT       NOT NULL REFERENCES public.tenants(id),
    team_id                     BIGINT       NOT NULL REFERENCES public.teams(id),
    title                       VARCHAR(100) NOT NULL,
    format                      VARCHAR(30)  NOT NULL,
    -- US20.2.1: populated iff format = 'CUSTOM' (RetroSessionService cross-field validation) —
    -- nullable, no default, never a separate ALTER TABLE (single-file convention).
    custom_format_id            UUID         REFERENCES agilite.retro_formats(id),
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
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- US20.2.1: same structural-guarantee approach as chk_retro_cards_anonymous_no_author below
    -- — the format/customFormatId invariant is also enforced at the DB layer, not only in
    -- RetroSessionService, so a future direct insert/update (admin tool, bulk fix, bug in a
    -- later US) can never silently create an inconsistent row.
    CONSTRAINT chk_retro_sessions_custom_format_id
        CHECK ((format = 'CUSTOM') = (custom_format_id IS NOT NULL))
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

-- US20.1.2b: per-(session, participant) dot-vote balance. voter_token is the opaque
-- access-token string minted by RetroSessionAccessService.join() (RetroChannelInterceptor's
-- ACCESS_TOKEN_HEADER) — reused as-is as the vote-balance identity key, exactly like the
-- existing rate-limit Redis keys, so it works uniformly for authenticated and anonymous
-- participants alike. votes_used is only ever mutated via the guarded atomic UPDATEs in
-- RetroVoteBalanceRepository (incrementIfAvailable/decrementIfPositive) — the CHECK constraint
-- below is the DB-level backstop proving it can never go negative or exceed votes_allowed, not
-- merely an app-level promise.
CREATE TABLE IF NOT EXISTS agilite.retro_vote_balances (
    id             UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    session_id     UUID        NOT NULL REFERENCES agilite.retro_sessions(id) ON DELETE CASCADE,
    voter_token    VARCHAR(64) NOT NULL,
    votes_used     INTEGER     NOT NULL DEFAULT 0,
    votes_allowed  INTEGER     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_retro_vote_balances_session_voter UNIQUE (session_id, voter_token),
    CONSTRAINT chk_retro_vote_balances_used_bounds CHECK (votes_used >= 0 AND votes_used <= votes_allowed)
);
CREATE INDEX IF NOT EXISTS idx_retro_vote_balances_session_id ON agilite.retro_vote_balances(session_id);

-- US20.1.2b: individual dot-votes — one row per vote (a participant may cast several votes on
-- the same card, so (session_id, card_id, voter_token) is deliberately not unique). Never carries
-- voter identity outside this table: VOTE_CAST/VOTE_UNCAST broadcasts only ever expose the
-- per-card aggregate count (see RetroVoteService), never voter_token.
CREATE TABLE IF NOT EXISTS agilite.retro_votes (
    id           UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    session_id   UUID        NOT NULL REFERENCES agilite.retro_sessions(id) ON DELETE CASCADE,
    card_id      UUID        NOT NULL REFERENCES agilite.retro_cards(id) ON DELETE CASCADE,
    voter_token  VARCHAR(64) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_retro_votes_card_id ON agilite.retro_votes(card_id);
CREATE INDEX IF NOT EXISTS idx_retro_votes_session_voter ON agilite.retro_votes(session_id, voter_token);

-- US20.3.1: actions created by any team member (not just the facilitator) during a session's
-- ACTION phase. team_id AND tenant_id are both denormalized from the owning session at creation
-- time (never re-derived via a join) rather than merely inferred through session_id:
--   - GET /retro/teams/{teamId}/actions lists every action for a team across every session (past
--     and present, including CLOSED ones per US20.3.1's AC) directly off team_id, with no join
--     back through agilite.retro_sessions.
--   - PATCH /retro/actions/{actionId} has neither sessionId nor teamId in its path — tenant_id
--     lets RetroActionService enforce tenant isolation directly, and team_id lets it check
--     team-membership, without an extra join back through public.teams for every request.
-- source_card_id is nullable (an action need not originate from a specific card) and ON DELETE
-- SET NULL rather than CASCADE: retro_cards has no delete endpoint today, but a future one must
-- never silently destroy an action that was already created from a card.
CREATE TABLE IF NOT EXISTS agilite.retro_actions (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL REFERENCES public.tenants(id),
    team_id             BIGINT       NOT NULL REFERENCES public.teams(id),
    session_id          UUID         NOT NULL REFERENCES agilite.retro_sessions(id) ON DELETE CASCADE,
    source_card_id      UUID         REFERENCES agilite.retro_cards(id) ON DELETE SET NULL,
    title               VARCHAR(200) NOT NULL,
    owner_user_id       BIGINT       REFERENCES public.users(id),
    due_date            DATE,
    -- Free transitions between all 4 statuses (no strict state machine) — an ABANDONNEE action
    -- may be reopened, matching this US's AC exactly.
    status              VARCHAR(20)  NOT NULL DEFAULT 'A_FAIRE'
                             CHECK (status IN ('A_FAIRE', 'EN_COURS', 'TERMINEE', 'ABANDONNEE')),
    created_by_user_id  BIGINT       NOT NULL REFERENCES public.users(id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_retro_actions_team_id    ON agilite.retro_actions(team_id);
CREATE INDEX IF NOT EXISTS idx_retro_actions_session_id ON agilite.retro_actions(session_id);

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

-- US09.2.1 — planning poker tickets. A room has at most one ticket VOTING at a time: enforced
-- both by PokerTicketService (application guard, ActiveTicketExistsException/409) and by the
-- partial unique index below (structural guarantee, same precedent as agilite.wheel_entry's
-- partial unique indexes, US14.1.1). revealed_at is written exclusively by US09.2.2, never by
-- this migration's owning US.
CREATE TABLE IF NOT EXISTS agilite.poker_tickets (
    id          UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    room_id     UUID NOT NULL REFERENCES agilite.poker_rooms(id) ON DELETE CASCADE,
    title       VARCHAR(200) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'VOTING' CHECK (status IN ('VOTING', 'REVEALED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    revealed_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_poker_tickets_room_id ON agilite.poker_tickets(room_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_poker_tickets_room_voting
    ON agilite.poker_tickets(room_id) WHERE status = 'VOTING';

-- US09.2.1 — votes, one row per (ticket, participant). participant_key is a SHA-256 hex digest
-- of the room access-token grant (EN09.1) — never the raw token itself: PokerVoteService hashes
-- it before persisting, so a leak of this table alone never hands out a still-usable token
-- (defense in depth, votes outlive the token's own Redis TTL). Upsertable (change of vote before
-- reveal), enforced by the unique constraint below.
CREATE TABLE IF NOT EXISTS agilite.poker_votes (
    id               UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    ticket_id        UUID NOT NULL REFERENCES agilite.poker_tickets(id) ON DELETE CASCADE,
    participant_key  CHAR(64) NOT NULL,
    value            VARCHAR(10) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_poker_votes_ticket_participant UNIQUE (ticket_id, participant_key)
);
CREATE INDEX IF NOT EXISTS idx_poker_votes_ticket_id ON agilite.poker_votes(ticket_id);

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

-- lastDrawnEntryId: anti-repeat marker for US14.2.1's weighted draw — written by
-- WheelDrawService after each spin, NULL until the wheel's first draw.
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

-- US14.2.1: wheel_draw (draw history) — one row per spin (wheelId, entryId, timestamp), plus a
-- frozen entry_label snapshot (survives the entry being removed later via PUT /wheels/{id}, same
-- snapshot rationale as wheel_entry.label for TEAM_MEMBER entries). entry_id is nullable/ON
-- DELETE SET NULL rather than CASCADE: removing an entry from a wheel must not erase the fact
-- that it was drawn in the past. wheel_id is ON DELETE CASCADE: history has no meaning once the
-- wheel itself is gone.
CREATE TABLE IF NOT EXISTS agilite.wheel_draw (
    id          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    wheel_id    UUID         NOT NULL REFERENCES agilite.wheel(id) ON DELETE CASCADE,
    entry_id    UUID         REFERENCES agilite.wheel_entry(id) ON DELETE SET NULL,
    entry_label VARCHAR(150) NOT NULL,
    drawn_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_wheel_draw_wheel_id_drawn_at ON agilite.wheel_draw(wheel_id, drawn_at DESC);
