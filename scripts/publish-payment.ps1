# Publishes payment.settled, NOT a ledger transaction or caller-supplied entries.
# PaymentJson contract:
# {"eventId":"demo-event","paymentId":"demo-payment","merchantAccountId":"alice","amount":"12.50","currency":"EUR"}
# Optional requestId/description may be omitted or null. Merchant must already exist.
param(
    [Parameter(Mandatory = $true)]
    [string] $PaymentJson
)
$ErrorActionPreference = "Stop"
$user = if ($env:LEDGER_RABBIT_USER) { $env:LEDGER_RABBIT_USER } else { "ledger" }
$password = if ($env:LEDGER_RABBIT_PASSWORD) { $env:LEDGER_RABBIT_PASSWORD } else { "local-ledger" }
$pair = "{0}:{1}" -f $user, $password
$basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
$publish = @{
    properties = @{ delivery_mode = 2; content_type = "application/json"; priority = 0 }
    routing_key = "payment.settled"
    payload = $PaymentJson
    payload_encoding = "string"
} | ConvertTo-Json -Depth 6
Invoke-RestMethod -Method Post `
    -Uri "http://127.0.0.1:15672/api/exchanges/%2F/ledger.payment.ingress/publish" `
    -Headers @{ Authorization = "Basic $basic" } `
    -ContentType "application/json" -Body $publish
