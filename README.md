# LedgerApp

A Java 17 / Spring Boot 4 double-entry ledger using MySQL, jOOQ, Flyway and
RabbitMQ. This is a local synthetic-data demo, not a custody service.

## Run and verify

Docker with Compose, Git and JDK 17 are required for full-stack verification.
From the repository root, run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify.ps1
```

or:

```sh
sh scripts/verify.sh
```

The script builds the app, starts MySQL, RabbitMQ and two application
instances in a uniquely named Compose project, then runs unit and real-stack
integration tests. It removes only that project's containers and disposable
database volume; it does not touch existing local database volumes.

For an interactive stack, choose and retain a unique project name:

```powershell
$project = "ledger-demo-$([guid]::NewGuid().ToString('N').Substring(0,12))"
docker compose -p $project up --build -d mysql rabbitmq app1 app2
# API: http://localhost:8080 and http://localhost:8081
# OpenAPI: http://localhost:8080/openapi.yaml
docker compose -p $project down
```

`down` leaves that project's database volume intact; `down -v` deletes it.
Flyway applies all migrations automatically to a fresh database.

If reusing an older demo database, stop both app instances before applying V4,
which changes identifier comparison and canonicalizes legacy entry aliases.
For a disposable demo, a fresh uniquely named Compose project avoids this
upgrade step without affecting another project's data.

Local compilation and unit tests need only the Maven wrapper and JDK:

```powershell
.\mvnw.cmd test
```

The opt-in jOOQ generator uses a fresh disposable database:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\generate-jooq.ps1
```

Generated classes live in `src/main/java/ledger/persistence/jooq`; do not
hand-edit them. Flyway migrations in `src/main/resources/db/migration`
define the schema used for generation.

## Minimal API example

All POSTs require an `Idempotency-Key`. Amounts are decimal JSON strings.
Account IDs, payment IDs and idempotency keys are case-sensitive: `alice`
and `ALICE` are distinct accounts. An omitted `overdraftLimit` defaults to
zero; an explicit null is invalid.

The written contract is `src/main/resources/static/openapi.yaml`. Requests
reject unknown JSON properties. Framework errors retain HTTP statuses such
as 404 and 405, with structured problem bodies and applicable headers.

```sh
curl -i http://localhost:8080/api/v1/accounts \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-account' \
  -d '{"accountId":"alice","currency":"EUR","overdraftLimit":"25.00"}'

curl -i http://localhost:8080/api/v1/transactions \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-fund' \
  -d '{"currency":"EUR","description":"Example funding","entries":[
    {"accountId":"SETTLEMENT-EUR","direction":"DEBIT","amount":"10.00"},
    {"accountId":"alice","direction":"CREDIT","amount":"10.00"}]}'

# Replace TRANSACTION_UUID with the transactionId returned above.
curl http://localhost:8080/api/v1/accounts/alice
curl http://localhost:8080/api/v1/transactions/TRANSACTION_UUID
```

Reverse with `POST /api/v1/transactions/{transactionId}/reversals`, also with
an idempotency key. A reversal is a new transaction with flipped entries; the
original remains immutable. Accounts expose their current balance. Transactions
are read in their persisted entry order. Account-entry listing and historical
balance endpoints are intentionally not provided.

## Money and payments

Amounts are exact `BigDecimal` values and MySQL `DECIMAL(20,2)` values; no
rounding or JSON numeric amounts are accepted. EUR/USD allow at most two
fractional digits and JPY allows none. Each transaction has at least two
positive entries, one currency, and equal debit/credit totals. Final
per-account balances must satisfy the configured overdraft and signed DECIMAL
bounds. Intermediate posting balances are not stored or checked: only the
transaction's final aggregate is relevant.

Settlement accounts `SETTLEMENT-EUR`, `SETTLEMENT-USD` and `SETTLEMENT-JPY`
are seeded with unlimited economic overdraft (API `overdraftLimit: null`).
They remain subject to DECIMAL storage bounds. Payment messages describe a
settled payment; the service constructs a debit to the matching settlement
account and a credit to the existing merchant account. See
`scripts/publish-payment.ps1` for the sample publisher. With the stack running
and `alice` already opened, publish from PowerShell:

```powershell
.\scripts\publish-payment.ps1 -PaymentJson '{"eventId":"demo-event","paymentId":"demo-payment","merchantAccountId":"alice","amount":"12.50","currency":"EUR"}'
```

The publisher uses RabbitMQ's local management API on port 15672. Override
its demo credentials with `LEDGER_RABBIT_USER` and `LEDGER_RABBIT_PASSWORD`
when needed. A `routed: true` response confirms routing, not ledger commit;
read the account balance to observe processing. Reusing the payment ID with
the same economics does not post again.

## Five design decisions

1. **Gate-first writes:** every mutation locks one permanent MySQL gate row
   before any other query. This deliberately serializes writes across both
   instances, including opposite-direction transfers, for a simple correctness
   argument. Reads and broker I/O do not hold the gate.
2. **jOOQ persistence:** Flyway migrations define the schema; checked-in
   generated jOOQ types build bound queries behind focused persistence classes.
   `scripts/generate-jooq.ps1` regenerates them against an isolated database.
3. **HTTP receipts:** permanent receipts store canonical request identity and
   the original response bytes/status/Location. Same-key retries replay that
   response across instances; changed requests are refused. Deterministic
   business refusals are receipted without ledger effects; infrastructure
   failures roll back.
4. **Payment identity:** deduplication uses `paymentId` plus merchant, currency
   and normalized amount, not delivery `eventId`. Economic conflicts are
   quarantined rather than posted again.
5. **Transactional outbox:** transaction and outbox rows commit atomically.
   Persistent mandatory RabbitMQ publishes are marked delivered only after a
   positive publisher confirmation and no return.

## Guarantees and limits

Transaction announcements are **at least once**. A crash after broker
confirmation but before the delivery mark can cause a duplicate; the stable
message/event ID is the transaction ID, so consumers should deduplicate. No
arrival-order guarantee is made. Payment poison is durably quarantined before
acknowledgement; transient processing failures are retried and exhausted
messages go to the durable dead-letter queue. Ledger entries and transaction
headers have database update/delete triggers. Idempotency receipts are
permanent.

The two-instance integration suite uses real MySQL and RabbitMQ and covers
replay/refusal, overdraft and opposite-transfer races, reversals and
immutability, payment dedup/quarantine, rollback, zero sums and outbox
republication. Unit tests cover exact money, request validation, receipts and
final-balance planning. Abrupt process failover, sustained load, operational
monitoring, backups, authentication, authorization and tenant isolation are
outside this demo's scope. Compose binds published ports to localhost, but do
not expose the service to untrusted networks or use it for real funds.

AI assistance was used to draft implementation, tests and documentation.
Verification claims are limited to the commands and results reported for the
current checkout.
