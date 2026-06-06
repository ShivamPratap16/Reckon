CREATE INDEX idx_txn_saga_state ON transactions (saga_state, updated_at)
    WHERE saga_state IS NOT NULL AND saga_state <> 'DONE';
