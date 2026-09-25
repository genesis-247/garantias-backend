#!/usr/bin/env bash
# Garantías 360 — ambiente de DEMOSTRACIÓN en Azure Container Apps con URL pública HTTPS.
# Requisitos: Azure CLI (az) con sesión iniciada, extensión containerapp, permisos en la suscripción.
# Uso:  ./azure-demo.sh <grupo-de-recursos> <region>      (ej. ./azure-demo.sh rg-g360-demo eastus2)
#
# IMPORTANTE: el perfil demo acepta la identidad por cabeceras (selector de perfil). Por eso el
# script activa la autenticación integrada de Container Apps con Entra ID (solo usuarios del tenant
# del Banco o de la fábrica). Nunca lo expongas sin esa protección.
set -euo pipefail
RG=${1:?grupo de recursos}; REGION=${2:-eastus2}
SUFIJO=$RANDOM
ACR=acrg360demo$SUFIJO; ENTORNO=cae-g360-demo; PG=pg-g360-demo-$SUFIJO
CLAVE_PG=$(openssl rand -base64 24 | tr -d '/+=')
RAIZ=$(cd "$(dirname "$0")/../.." && pwd)

az group create -n "$RG" -l "$REGION" -o none
az acr create -g "$RG" -n "$ACR" --sku Basic --admin-enabled true -o none
echo "Construyendo imágenes en ACR…"
az acr build -r "$ACR" -t g360-backend:demo "$RAIZ/garantias-backend" -o none

echo "Base de datos PostgreSQL Flexible Server…"
az postgres flexible-server create -g "$RG" -n "$PG" -l "$REGION" --tier Burstable --sku-name Standard_B1ms \
  --version 16 --admin-user g360 --admin-password "$CLAVE_PG" --public-access 0.0.0.0 --storage-size 32 -o none
az postgres flexible-server db create -g "$RG" -s "$PG" -d garantias360 -o none

az containerapp env create -g "$RG" -n "$ENTORNO" -l "$REGION" -o none
ACR_CLAVE=$(az acr credential show -n "$ACR" --query 'passwords[0].value' -o tsv)

az containerapp create -g "$RG" -n g360-backend --environment "$ENTORNO" --image "$ACR.azurecr.io/g360-backend:demo" \
  --registry-server "$ACR.azurecr.io" --registry-username "$ACR" --registry-password "$ACR_CLAVE" \
  --ingress internal --target-port 8080 --cpu 1 --memory 2Gi --min-replicas 1 \
  --secrets db-clave="$CLAVE_PG" \
  --env-vars SPRING_PROFILES_ACTIVE=demo G360_DB_URL="jdbc:postgresql://$PG.postgres.database.azure.com:5432/garantias360?sslmode=require" \
             G360_DB_USUARIO=g360 G360_DB_CLAVE=secretref:db-clave -o none
BACKEND=$(az containerapp show -g "$RG" -n g360-backend --query properties.configuration.ingress.fqdn -o tsv)

az acr build -r "$ACR" -t g360-frontend:demo --build-arg BACKEND_URL="https://$BACKEND" "$RAIZ/garantias-frontend" -o none
az containerapp create -g "$RG" -n g360-frontend --environment "$ENTORNO" --image "$ACR.azurecr.io/g360-frontend:demo" \
  --registry-server "$ACR.azurecr.io" --registry-username "$ACR" --registry-password "$ACR_CLAVE" \
  --ingress external --target-port 3000 --cpu 0.5 --memory 1Gi --min-replicas 1 -o none
URL=https://$(az containerapp show -g "$RG" -n g360-frontend --query properties.configuration.ingress.fqdn -o tsv)

cat <<FIN

Garantías 360 desplegado: $URL
Paso obligatorio antes de compartir la URL — proteger con Entra ID:
  1. Registra una aplicación en Entra ID con redirect URI: $URL/.auth/login/aad/callback
  2. az containerapp auth microsoft update -g $RG -n g360-frontend --client-id <APP_ID> \\
       --client-secret <SECRETO> --tenant-id <TENANT_ID> --yes
  3. az containerapp auth update -g $RG -n g360-frontend --unauthenticated-client-action RedirectToLoginPage --enabled true
Para eliminar todo el ambiente:  az group delete -n $RG
FIN
