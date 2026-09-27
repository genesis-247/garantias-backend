# Garantías 360 — arranque local en Windows, sin Docker ni PostgreSQL instalado.
# Requisitos: Java 21 y Node.js 20 o superior. Clona garantias-frontend al lado de garantias-backend.
# Uso (PowerShell, desde garantias-backend):   .\local\iniciar.ps1
#   -BaseReal   usa la base de datos definida en G360_DB_URL / G360_DB_USUARIO / G360_DB_CLAVE (sin datos demo)
param([switch]$BaseReal)
$ErrorActionPreference = "Stop"
$back = Split-Path -Parent $PSScriptRoot
$front = Join-Path (Split-Path -Parent $back) "garantias-frontend"

foreach ($c in "java", "node", "npm") {
  if (-not (Get-Command $c -ErrorAction SilentlyContinue)) { throw "Falta '$c' en el PATH. Instala Java 21 y Node.js 20+." }
}
if (-not (Test-Path $front)) { throw "No encuentro $front. Clona garantias-frontend al lado de garantias-backend." }

if ($BaseReal) {
  if (-not $env:G360_DB_URL) { throw "Define G360_DB_URL, G360_DB_USUARIO y G360_DB_CLAVE para usar la base real." }
  $env:SPRING_PROFILES_ACTIVE = "local"
  $env:G360_DB_EMBEBIDA = "false"
  Write-Host "Backend contra la base real: $env:G360_DB_URL"
} else {
  $env:SPRING_PROFILES_ACTIVE = "demo"
  $env:G360_DB_EMBEBIDA = "true"
  Write-Host "Backend con PostgreSQL embebido (datos en $HOME\.garantias360\postgres)"
}

$log = Join-Path $env:TEMP "garantias360-backend.log"
# La configuración va por variables de entorno (sin comillas anidadas que cmd.exe pueda alterar).
Start-Process -FilePath (Join-Path $back "gradlew.bat") -ArgumentList "bootRun" `
  -WorkingDirectory $back -RedirectStandardOutput $log -WindowStyle Minimized
Write-Host "Esperando el backend (la primera vez descarga dependencias y carga datos demo; puede tardar unos minutos)…"
$listo = $false
for ($i = 0; $i -lt 180; $i++) {
  try { Invoke-WebRequest -UseBasicParsing "http://localhost:8080/actuator/health" | Out-Null; $listo = $true; break } catch { Start-Sleep 3 }
}
if (-not $listo) { throw "El backend no respondió. Revisa $log" }
Write-Host "Backend listo: http://localhost:8080/swagger-ui.html"

Push-Location $front
if (-not (Test-Path "node_modules")) { npm install --no-audit --no-fund }
$env:BACKEND_URL = "http://localhost:8080"
Start-Process "http://localhost:3000"
Write-Host "Frontend: http://localhost:3000  (Ctrl+C para detenerlo; el backend sigue en su ventana minimizada)"
npm run dev
Pop-Location
