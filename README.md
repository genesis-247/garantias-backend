# Garantías 360 — Backend

Maestro de garantías de Banco Popular: registro, ciclo de vida, cobertura explicable y reproducible, reglas no-code con maker–checker, auditoría inmutable encadenada por hash y eventos con outbox. Especificación en [`docs/especificacion`](docs/especificacion/ESPECIFICACION_FUNCIONAL.md).

**Stack:** Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · Kafka (opcional en local) · OpenAPI (springdoc).

## Ejecutar en local

```bash
# 1. PostgreSQL con una base vacía
createuser g360 -P            # clave: g360
createdb garantias360 -O g360

# 2. Backend con datos sintéticos de demostración (perfil demo)
./gradlew bootRun --args='--spring.profiles.active=demo'
```

- API: `http://localhost:8080/api/v1` · Swagger UI: `http://localhost:8080/swagger-ui.html`
- En los perfiles `local` y `demo` la identidad llega por cabeceras (`X-Usuario`, `X-Roles`), para poder demostrar maker–checker sin un tenant de Entra ID. **Ese mecanismo no existe en otros perfiles**: en ambientes integrados se usa OAuth 2.0 / OIDC con Entra ID y los app roles del token.
- El perfil `demo` carga datos **sintéticos** solo si la base está vacía (tipos de garantía, reglas ilustrativas aprobadas con maker–checker y unas 150 garantías que incluyen los casos del documento de visión).

| Variable | Uso | Por defecto |
|---|---|---|
| `G360_DB_URL`, `G360_DB_USUARIO`, `G360_DB_CLAVE` | Base de datos | `localhost:5432/garantias360`, `g360`/`g360` |
| `G360_KAFKA_HABILITADO`, `G360_KAFKA_SERVIDORES` | Publicar y consumir en Kafka | `false` (los eventos se registran en el log) |
| `G360_ENTRA_ISSUER`, `G360_ENTRA_AUDIENCIA` | Validación de JWT de Entra ID | — |
| `G360_CORS` | Orígenes permitidos del front | `http://localhost:3000` |

## Pruebas

```bash
./gradlew test                                   # unitarias (motor de cobertura, reglas)
G360_TEST_DB_URL=jdbc:postgresql://localhost:5432/garantias360_test ./gradlew test   # + integración (limpia esa base)
```

- `MotorCoberturaTest`: ejemplo resuelto del anexo A (86,67 % secuencial; 117,19 % / 97,08 % prorrata), casos CT-01…CT-20 y 200 corridas aleatorias de invariantes (suma asignada ≤ valor neto, sin negativos, topes respetados, determinismo).
- `EvaluadorTablaTest`: tablas de decisión, validación y casos de prueba de reglas.
- `FlujoGarantiaIT`: flujo de punta a punta contra PostgreSQL real.

## Estructura

| Paquete | Contenido |
|---|---|
| `cobertura.motor` | Motor de cobertura puro (anexo A): sin Spring ni base de datos |
| `cobertura` | Orquestación, persistencia inmutable, simulación, reproducción |
| `reglas.motor` / `reglas` | Tablas de decisión (anexo B) y ciclo de vida con maker–checker |
| `garantia` | Maestro, ciclo de vida, valoraciones, vínculos, idoneidad, Expediente 360 |
| `configuracion` | Tipos de garantía y campos personalizados (modelo Proceder) |
| `auditoria` | Auditoría append-only encadenada por SHA-256 |
| `eventos` | Outbox, relay con reintentos y DLQ, consultas del core transaccional |
| `integracion` | Eventos de Flexcube (REST y Kafka) con inbox idempotente y linaje |
| `tablero` | Centro de mando y alertas |
| `api` | Controladores REST v1 |
| `demo` | Catálogo y datos sintéticos (solo perfil `demo`) |

## Alcance de este incremento

Módulos operativos: M01, M03, M04, M05 (básico), M06 (básico), M07, M08, M09, M10, M12 (bloqueo y flujo), M14, M15, M16 (API), M17 (API), M21 y la integración con Flexcube.

**Pendiente para los siguientes incrementos:**
- Appian (tareas humanas).
- OnBase y el expediente documental.
- Pólizas como entidad propia.
- Checklist jurídico y plantilla de actividades de constitución configurables.
- Cumplimiento SFC con planes de remediación.
- Carga masiva.
- Migración desde Shivam.
- Asistente IA (fase 2).
- Integraciones externas: Fasecolda, RGM, IGAC, RUNT, FNG y FNA.
