CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE users (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email         text NOT NULL UNIQUE,
    password_hash text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE accounts (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id   uuid NULL REFERENCES users(id),
    type       text NOT NULL,         -- USER_WALLET | BANK_SETTLEMENT | REWARDS_POOL | MERCHANT
    currency   text NOT NULL DEFAULT 'INR',
    balance    bigint NOT NULL DEFAULT 0,   -- PAISA
    version    bigint NOT NULL DEFAULT 0,
    status     text NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT balance_nonneg_except_system
        CHECK (balance >= 0 OR type IN ('BANK_SETTLEMENT','REWARDS_POOL'))
);

-- seed system accounts (fixed UUIDs so app code can reference them)
INSERT INTO accounts (id, owner_id, type) VALUES
  ('00000000-0000-0000-0000-000000000001', NULL, 'BANK_SETTLEMENT'),
  ('00000000-0000-0000-0000-000000000002', NULL, 'REWARDS_POOL');
