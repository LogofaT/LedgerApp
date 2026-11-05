$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
$project = "ledger-verify-$([guid]::NewGuid().ToString('N').Substring(0,12))"
try {
    docker compose -p $project up --quiet-build --build -d mysql rabbitmq app1 app2
    if ($LASTEXITCODE -ne 0) { throw "docker compose up failed: $LASTEXITCODE" }
    # The preceding up owns dependency startup; run must not recreate live databases/brokers.
    docker compose -p $project --profile test run --no-deps --rm integration
    $integrationExitCode = $LASTEXITCODE
    if ($integrationExitCode -ne 0) {
        docker compose -p $project logs --no-color --tail 250 app1 app2
        throw "integration suite failed: $integrationExitCode"
    }
}
finally {
    docker compose -p $project down -v --remove-orphans
}
