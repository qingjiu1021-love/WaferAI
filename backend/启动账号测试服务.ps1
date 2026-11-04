$ErrorActionPreference = 'Stop'
$env:WAFERAI_AUTH_DEV_OUTBOX = '1'
$env:WAFERAI_AUTH_PUBLIC_URL = 'http://10.0.2.2:8010'
Push-Location -LiteralPath $PSScriptRoot
try {
    & ./.venv/Scripts/python.exe -m uvicorn app.account_app:app --host 127.0.0.1 --port 8010 --no-access-log
} finally {
    Pop-Location
}
