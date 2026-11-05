CREATE TABLE ledger_gate (
  id TINYINT PRIMARY KEY,
  sequence_no BIGINT NOT NULL,
  logical_micros BIGINT NOT NULL
) ENGINE=InnoDB;
INSERT INTO ledger_gate(id, sequence_no, logical_micros) VALUES (1, 0, 0);

CREATE TABLE ledger_account (
  account_id VARCHAR(64) PRIMARY KEY,
  currency CHAR(3) NOT NULL,
  overdraft_limit DECIMAL(20,2) NOT NULL,
  balance DECIMAL(20,2) NOT NULL DEFAULT 0,
  settlement BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT chk_account_nonnegative_limit CHECK (overdraft_limit >= 0)
) ENGINE=InnoDB;

CREATE TABLE ledger_transaction (
  transaction_id CHAR(36) PRIMARY KEY,
  ledger_sequence BIGINT NOT NULL UNIQUE,
  logical_micros BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  description VARCHAR(240) NOT NULL,
  reversal_of CHAR(36) NULL UNIQUE,
  request_id VARCHAR(80) NULL,
  payment_id VARCHAR(100) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_reversal_target FOREIGN KEY (reversal_of) REFERENCES ledger_transaction(transaction_id),
  INDEX idx_transaction_logical(logical_micros)
) ENGINE=InnoDB;

CREATE TABLE ledger_entry (
  transaction_id CHAR(36) NOT NULL,
  entry_index SMALLINT NOT NULL,
  account_id VARCHAR(64) NOT NULL,
  direction ENUM('DEBIT','CREDIT') NOT NULL,
  amount DECIMAL(20,2) NOT NULL,
  post_entry_balance DECIMAL(20,2) NOT NULL,
  PRIMARY KEY(transaction_id, entry_index),
  CONSTRAINT fk_entry_tx FOREIGN KEY (transaction_id) REFERENCES ledger_transaction(transaction_id),
  CONSTRAINT fk_entry_account FOREIGN KEY (account_id) REFERENCES ledger_account(account_id),
  CONSTRAINT chk_entry_positive CHECK (amount > 0),
  INDEX idx_entry_account(account_id, transaction_id)
) ENGINE=InnoDB;

CREATE TABLE idempotency_receipt (
  operation VARCHAR(40) NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  fingerprint CHAR(64) NOT NULL,
  http_status SMALLINT NOT NULL,
  response_body LONGTEXT NOT NULL,
  location VARCHAR(240) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(operation, idempotency_key)
) ENGINE=InnoDB;

CREATE TABLE payment_receipt (
  payment_id VARCHAR(100) PRIMARY KEY,
  economic_fingerprint CHAR(64) NOT NULL,
  transaction_id CHAR(36) NULL,
  outcome VARCHAR(32) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_payment_tx FOREIGN KEY (transaction_id) REFERENCES ledger_transaction(transaction_id)
) ENGINE=InnoDB;

CREATE TABLE message_quarantine (
  quarantine_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id VARCHAR(120) NULL,
  payment_id VARCHAR(100) NULL,
  reason_code VARCHAR(60) NOT NULL,
  payload LONGTEXT NOT NULL,
  received_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;

CREATE TABLE transaction_outbox (
  event_id CHAR(36) PRIMARY KEY,
  transaction_id CHAR(36) NOT NULL,
  ledger_sequence BIGINT NOT NULL,
  payload JSON NOT NULL,
  published_at TIMESTAMP(6) NULL,
  attempts INT NOT NULL DEFAULT 0,
  last_error VARCHAR(240) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_outbox_tx FOREIGN KEY (transaction_id) REFERENCES ledger_transaction(transaction_id),
  INDEX idx_outbox_pending(published_at, created_at)
) ENGINE=InnoDB;

CREATE TRIGGER ledger_transaction_no_update BEFORE UPDATE ON ledger_transaction
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ledger transactions are immutable';
CREATE TRIGGER ledger_transaction_no_delete BEFORE DELETE ON ledger_transaction
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ledger transactions are immutable';
CREATE TRIGGER ledger_entry_no_update BEFORE UPDATE ON ledger_entry
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ledger entries are immutable';
CREATE TRIGGER ledger_entry_no_delete BEFORE DELETE ON ledger_entry
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ledger entries are immutable';

INSERT INTO ledger_account(account_id,currency,overdraft_limit,balance,settlement)
VALUES ('SETTLEMENT-EUR','EUR',999999999999999999.99,0,TRUE),
       ('SETTLEMENT-USD','USD',999999999999999999.99,0,TRUE),
       ('SETTLEMENT-JPY','JPY',999999999999999999.99,0,TRUE);
