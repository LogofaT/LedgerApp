-- NULL means no economic overdraft limit; signed DECIMAL storage bounds still apply.
-- Leave V1 unchanged so existing databases can migrate without checksum repair.
ALTER TABLE ledger_account MODIFY overdraft_limit DECIMAL(20,2) NULL;
UPDATE ledger_account SET overdraft_limit=NULL WHERE settlement=TRUE;
ALTER TABLE ledger_account ADD CONSTRAINT chk_account_limit_kind
  CHECK ((settlement=TRUE AND overdraft_limit IS NULL)
      OR (settlement=FALSE AND overdraft_limit IS NOT NULL));
