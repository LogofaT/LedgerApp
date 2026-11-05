-- Apply with application writers stopped: MySQL DDL is not transactional.
-- Canonicalize old case/accent/space aliases to the account they already
-- reference before tightening comparison. Amounts and balances are unchanged.
DROP TRIGGER ledger_entry_no_update;
UPDATE ledger_entry e
JOIN ledger_account a ON e.account_id = a.account_id
SET e.account_id = a.account_id
WHERE BINARY e.account_id <> BINARY a.account_id;
CREATE TRIGGER ledger_entry_no_update BEFORE UPDATE ON ledger_entry
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ledger entries are immutable';

ALTER TABLE ledger_entry DROP FOREIGN KEY fk_entry_account;
ALTER TABLE ledger_account
  MODIFY account_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL;
ALTER TABLE ledger_entry
  MODIFY account_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL;
ALTER TABLE ledger_entry
  ADD CONSTRAINT fk_entry_account FOREIGN KEY (account_id) REFERENCES ledger_account(account_id);

ALTER TABLE idempotency_receipt
  MODIFY idempotency_key VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL;
ALTER TABLE payment_receipt
  MODIFY payment_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL;
ALTER TABLE ledger_transaction
  MODIFY payment_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL;
ALTER TABLE message_quarantine
  MODIFY payment_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL;
