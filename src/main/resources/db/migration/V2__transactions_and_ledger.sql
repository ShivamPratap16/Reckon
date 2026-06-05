CREATE TABLE transactions (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    type            text NOT NULL,   -- ADD_MONEY | P2P | PAY_MERCHANT | CASHBACK
    status          text NOT NULL,   -- PENDING | COMPLETED | FAILED | COMPENSATED
    idempotency_key text NOT NULL,
    request_hash    text NOT NULL,
    amount          bigint NOT NULL,
    initiator_id    uuid NULL,
    from_account_id uuid NULL REFERENCES accounts(id),
    to_account_id   uuid NULL REFERENCES accounts(id),
    saga_state      text NULL,
    failure_reason  text NULL,
    response_code   int NULL,
    response_body   jsonb NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT amount_positive CHECK (amount > 0),
    CONSTRAINT uq_initiator_idem UNIQUE (initiator_id, idempotency_key)
);

CREATE TABLE ledger_entries (
    id             bigserial PRIMARY KEY,
    transaction_id uuid NOT NULL REFERENCES transactions(id),
    account_id     uuid NOT NULL REFERENCES accounts(id),
    direction      text NOT NULL,   -- DEBIT | CREDIT
    amount         bigint NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT entry_amount_positive CHECK (amount > 0),
    CONSTRAINT uq_txn_account_direction UNIQUE (transaction_id, account_id, direction)
);

CREATE INDEX idx_entries_account_id ON ledger_entries (account_id, id);
CREATE INDEX idx_entries_txn ON ledger_entries (transaction_id);
CREATE INDEX idx_txn_status_created ON transactions (status, created_at);
