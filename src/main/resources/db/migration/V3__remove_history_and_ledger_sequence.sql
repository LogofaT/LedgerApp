-- Keep the existing persisted created_at timestamp as the transaction's
-- recorded time. Do not update immutable transaction rows or reserialize
-- existing outbox payloads / idempotency receipts.
-- Immutable transaction and entry triggers remain installed throughout.
ALTER TABLE ledger_entry
  DROP COLUMN post_entry_balance;

ALTER TABLE ledger_transaction
  DROP INDEX ledger_sequence,
  DROP INDEX idx_transaction_logical,
  DROP COLUMN ledger_sequence,
  DROP COLUMN logical_micros;

ALTER TABLE ledger_gate
  DROP COLUMN sequence_no,
  DROP COLUMN logical_micros;

ALTER TABLE transaction_outbox
  DROP COLUMN ledger_sequence;
