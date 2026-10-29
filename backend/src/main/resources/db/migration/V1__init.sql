-- One row per H3 cell that has ever been ordered in or given capacity.
-- Cells nobody has touched are not stored; reads treat them as having the default capacity.
CREATE TABLE cells (
    h3_index      text PRIMARY KEY,
    capacity      integer NOT NULL CHECK (capacity >= 0),
    reserved      integer NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    -- Last waitlist position handed out. A counter on the cell row instead of a sequence:
    -- sequences are non-transactional, so a rolled-back join would leave a gap.
    waitlist_next bigint  NOT NULL DEFAULT 0,
    -- How many of those positions have been promoted. FIFO with no cancellations means the
    -- waiting entries are exactly positions (waitlist_promoted, waitlist_next], so depth is O(1).
    waitlist_promoted bigint NOT NULL DEFAULT 0,
    -- Belt and braces: the application never relies on this, but if a code path ever
    -- forgot the conditional update, the statement would fail instead of overselling.
    CONSTRAINT cells_not_oversold CHECK (reserved <= capacity)
);

CREATE TABLE orders (
    id               uuid PRIMARY KEY,
    h3_index         text        NOT NULL REFERENCES cells (h3_index),
    email            text        NOT NULL,
    full_name        text        NOT NULL,
    locale           text        NOT NULL,
    status           text        NOT NULL CHECK (status IN ('RESERVED', 'CONFIRMED', 'CANCELLED')),
    payment_status   text        NOT NULL CHECK (payment_status IN ('NONE', 'PENDING', 'CAPTURED', 'DECLINED')),
    payment_attempts integer     NOT NULL DEFAULT 0,
    payment_token    text,
    deposit_minor    bigint      NOT NULL,
    currency         char(3)     NOT NULL,
    source           text        NOT NULL CHECK (source IN ('DIRECT', 'WAITLIST')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

-- One live order per customer per cell; also stops a second slot being taken by retries
-- that arrive with a fresh Idempotency-Key.
CREATE UNIQUE INDEX orders_one_live_per_email ON orders (h3_index, email) WHERE status <> 'CANCELLED';
-- The reconciliation job only ever looks at payments in an unknown state.
CREATE INDEX orders_pending_payment ON orders (updated_at) WHERE payment_status = 'PENDING';

CREATE TABLE waitlist_entries (
    id          bigserial PRIMARY KEY,
    h3_index    text        NOT NULL REFERENCES cells (h3_index),
    position    bigint      NOT NULL,
    email       text        NOT NULL,
    full_name   text        NOT NULL,
    locale      text        NOT NULL,
    status      text        NOT NULL CHECK (status IN ('WAITING', 'PROMOTED')),
    order_id    uuid REFERENCES orders (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    promoted_at timestamptz,
    UNIQUE (h3_index, position)
);

CREATE UNIQUE INDEX waitlist_one_waiting_per_email ON waitlist_entries (h3_index, email) WHERE status = 'WAITING';
CREATE INDEX waitlist_fifo ON waitlist_entries (h3_index, position) WHERE status = 'WAITING';

-- Idempotency-Key -> the resource the first request created. The row is written in the
-- same transaction as the order/waitlist entry, so it exists if and only if that work committed.
CREATE TABLE idempotency_keys (
    key               text PRIMARY KEY,
    request_hash      text        NOT NULL,
    order_id          uuid REFERENCES orders (id),
    waitlist_entry_id bigint REFERENCES waitlist_entries (id),
    created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idempotency_keys_created ON idempotency_keys (created_at);

-- Transactional outbox: rows are inserted in the same transaction as the state change that
-- causes the notification, and a worker delivers them afterwards (at least once).
CREATE TABLE outbox (
    id           bigserial PRIMARY KEY,
    kind         text        NOT NULL,
    recipient    text        NOT NULL,
    payload      jsonb       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    attempts     integer     NOT NULL DEFAULT 0,
    last_error   text,
    delivered_at timestamptz
);

CREATE INDEX outbox_undelivered ON outbox (id) WHERE delivered_at IS NULL;
