CREATE TABLE outbox (
    id           bigserial PRIMARY KEY,
    event_id     uuid NOT NULL DEFAULT gen_random_uuid(),
    aggregate_id uuid NOT NULL,
    event_type   text NOT NULL,
    payload      jsonb NOT NULL,
    published    boolean NOT NULL DEFAULT false,
    attempts     int NOT NULL DEFAULT 0,
    last_error   text NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz NULL
);
-- partial index so the poller never full-scans as the table grows:
CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published = false;
