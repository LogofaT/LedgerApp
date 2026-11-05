$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
$project = "ledger-jooq-codegen-$([guid]::NewGuid().ToString('N').Substring(0,12))"
$oldMysqlPort = $env:JOOQ_MYSQL_PORT
$oldJdbcUrl = $env:JOOQ_JDBC_URL
$oldJdbcUser = $env:JOOQ_JDBC_USER
$oldJdbcPassword = $env:JOOQ_JDBC_PASSWORD
if (-not $env:JOOQ_MYSQL_PORT) {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $env:JOOQ_MYSQL_PORT = "$($listener.LocalEndpoint.Port)"
    $listener.Stop()
}
$port = [int]$env:JOOQ_MYSQL_PORT
$compose = @("compose", "-p", $project, "-f", "compose.codegen.yaml")

try {
    & docker @compose up -d mysql
    if ($LASTEXITCODE -ne 0) { throw "Disposable Flyway database startup failed: $LASTEXITCODE" }

    $deadline = (Get-Date).AddMinutes(3)
    do {
        Start-Sleep -Seconds 2
        $containerId = (& docker @compose ps -q mysql)
        if ($containerId) {
            $health = (& docker inspect --format '{{.State.Health.Status}}' $containerId)
            if ($health -eq "healthy") { break }
        }
    } while ((Get-Date) -lt $deadline)
    if ($health -ne "healthy") { throw "Disposable MySQL did not become ready" }

    $env:JOOQ_JDBC_URL = "jdbc:mysql://127.0.0.1:$port/ledger?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    $env:JOOQ_JDBC_USER = "ledger"
    $env:JOOQ_JDBC_PASSWORD = "local-ledger"
    & .\mvnw.cmd -B -q -Pjooq-codegen flyway:migrate
    if ($LASTEXITCODE -ne 0) { throw "Flyway migration of the disposable database failed: $LASTEXITCODE" }
    $migrated = $false
    $deadline = (Get-Date).AddMinutes(3)
    do {
        Start-Sleep -Seconds 2
        $migration = (& docker @compose exec -T -e MYSQL_PWD=local-ledger mysql mysql -uledger ledger -N -e "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IN ('1','2','3','4')")
        if ($migration -match "^4$") { $migrated = $true; break }
    } while ((Get-Date) -lt $deadline)
    if (-not $migrated) { throw "Flyway did not apply V1, V2, V3 and V4 to the disposable database" }

    & .\mvnw.cmd -B -q -Pjooq-codegen jooq-codegen:generate
    if ($LASTEXITCODE -ne 0) { throw "jOOQ code generation failed: $LASTEXITCODE" }
}
finally {
    & docker @compose down -v --remove-orphans
    $env:JOOQ_MYSQL_PORT = $oldMysqlPort
    $env:JOOQ_JDBC_URL = $oldJdbcUrl
    $env:JOOQ_JDBC_USER = $oldJdbcUser
    $env:JOOQ_JDBC_PASSWORD = $oldJdbcPassword
}
