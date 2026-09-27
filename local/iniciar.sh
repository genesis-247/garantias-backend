#!/usr/bin/env bash
# Garantías 360 — arranque local en macOS/Linux, sin Docker ni PostgreSQL instalado.
# Requisitos: Java 21 y Node.js 20+. Clona garantias-frontend al lado de garantias-backend.
# Uso (desde garantias-backend):  ./local/iniciar.sh            → PostgreSQL embebido + datos demo
#                                ./local/iniciar.sh --base-real → usa G360_DB_URL/G360_DB_USUARIO/G360_DB_CLAVE
set -euo pipefail
BACK=$(cd "$(dirname "$0")/.." && pwd)
FRONT=$(cd "$BACK/.." && pwd)/garantias-frontend
for c in java node npm; do command -v $c >/dev/null || { echo "Falta '$c'. Instala Java 21 y Node.js 20+."; exit 1; }; done
[ -d "$FRONT" ] || { echo "No encuentro $FRONT. Clona garantias-frontend al lado de garantias-backend."; exit 1; }

if [ "${1:-}" = "--base-real" ]; then
  : "${G360_DB_URL:?Define G360_DB_URL, G360_DB_USUARIO y G360_DB_CLAVE}"
  export SPRING_PROFILES_ACTIVE=local G360_DB_EMBEBIDA=false
  echo "Backend contra la base real: $G360_DB_URL"
else
  export SPRING_PROFILES_ACTIVE=demo G360_DB_EMBEBIDA=true
  echo "Backend con PostgreSQL embebido (datos en ~/.garantias360/postgres)"
fi

LOG=${TMPDIR:-/tmp}/garantias360-backend.log
(cd "$BACK" && ./gradlew bootRun > "$LOG" 2>&1) &
BACK_PID=$!
trap 'kill $BACK_PID 2>/dev/null' EXIT
echo "Esperando el backend (la primera vez descarga dependencias y carga datos demo)…"
for _ in $(seq 1 180); do curl -fs http://localhost:8080/actuator/health >/dev/null 2>&1 && break; sleep 3; done
curl -fs http://localhost:8080/actuator/health >/dev/null || { echo "El backend no respondió. Revisa $LOG"; exit 1; }
echo "Backend listo: http://localhost:8080/swagger-ui.html"

cd "$FRONT"
[ -d node_modules ] || npm install --no-audit --no-fund
echo "Frontend: http://localhost:3000  (Ctrl+C detiene ambos)"
BACKEND_URL=http://localhost:8080 npm run dev
