ALTER TABLE accounts ADD COLUMN reserved_balance bigint NOT NULL DEFAULT 0
    CONSTRAINT reserved_nonneg CHECK (reserved_balance >= 0);

CREATE TABLE holds (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key  text NOT NULL,
    initiator_id     uuid NULL,
    payer_account_id uuid NOT NULL REFERENCES accounts(id),
    payee_account_id uuid NOT NULL REFERENCES accounts(id),
    amount           bigint NOT NULL CONSTRAINT hold_amount_positive CHECK (amount > 0),
    captured_amount  bigint NOT NULL DEFAULT 0,
    status           text NOT NULL,        -- HELD | CAPTURED | VOIDED | EXPIRED
    capture_txn_id   uuid NULL REFERENCES transactions(id),
    expires_at       timestamptz NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_hold_initiator_idem UNIQUE (initiator_id, idempotency_key)
);
CREATE INDEX idx_holds_status_expiry ON holds (status, expires_at) WHERE status = 'HELD';
CREATE INDEX idx_holds_payer ON holds (payer_account_id) WHERE status = 'HELD';
