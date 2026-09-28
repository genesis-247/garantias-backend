-- Garantías 360 — esquema inicial (incremento 1).
-- Ver docs/especificacion/ESPECIFICACION_FUNCIONAL.md §8 (modelo de datos conceptual).

-- ---------------------------------------------------------------------------
-- Configuración de tipos de garantía (M16). Las versiones son inmutables una
-- vez publicadas: editar crea una versión nueva (RF-1601, RF-1607).
-- ---------------------------------------------------------------------------
create table tipo_garantia (
    id                 uuid primary key,
    codigo             text not null unique,
    nombre             text not null,
    clase              text not null,
    requiere_avaluo    boolean not null default false,
    requiere_poliza    boolean not null default false,
    registro_publico   text not null default 'NINGUNO',
    admite_multiples   boolean not null default true,
    activo             boolean not null default true,
    created_at         timestamptz not null default now()
);

create table tipo_garantia_version (
    id             uuid primary key,
    tipo_id        uuid not null references tipo_garantia (id),
    numero         int not null,
    campos         jsonb not null default '[]'::jsonb,
    estado         text not null check (estado in ('BORRADOR', 'PUBLICADA', 'RETIRADA')),
    creado_por     text not null,
    aprobado_por   text,
    created_at     timestamptz not null default now(),
    unique (tipo_id, numero)
);

-- ---------------------------------------------------------------------------
-- Obligaciones: referencia a Flexcube con el último saldo conocido. No es el
-- maestro de la obligación (R-02); los cálculos guardan su propio snapshot.
-- ---------------------------------------------------------------------------
create table obligacion (
    id                  uuid primary key,
    numero              text not null unique,
    cliente_documento   text not null,
    cliente_nombre      text not null,
    producto            text not null,
    segmento            text not null,
    moneda              text not null default 'COP',
    saldo_capital       numeric(20, 2) not null default 0,
    saldo_intereses     numeric(20, 2) not null default 0,
    saldo_otros         numeric(20, 2) not null default 0,
    estado              text not null check (estado in ('APROBADA', 'VIGENTE', 'CANCELADA', 'CASTIGADA')),
    dias_mora           int not null default 0,
    destino             text,
    fecha_desembolso    date,
    actualizado_en      timestamptz not null default now()
);
create index obligacion_cliente_idx on obligacion (cliente_documento);

-- ---------------------------------------------------------------------------
-- Maestro de garantías (M04).
-- ---------------------------------------------------------------------------
create table consecutivo_garantia (
    anio    int primary key,
    ultimo  int not null
);

create table garantia (
    id                         uuid primary key,
    codigo                     text not null unique,
    tipo_version_id            uuid not null references tipo_garantia_version (id),
    tipo_codigo                text not null,
    cliente_documento          text not null,
    cliente_nombre             text not null,
    producto                   text not null,
    segmento                   text not null,
    macroestado                text not null,
    estado_juridico            text not null default 'SIN_ESTUDIO',
    estado_documental          text not null default 'INCOMPLETO',
    idoneidad                  text not null default 'NO_EVALUADA',
    idoneidad_motivo           text,
    idoneidad_regla            text,
    moneda                     text not null default 'COP',
    valor_comercial            numeric(20, 2),
    valor_admisible            numeric(20, 2),
    valor_neto                 numeric(20, 2),
    gravamenes_previos         numeric(20, 2) not null default 0,
    fecha_ultima_valoracion    date,
    fecha_proxima_valoracion   date,
    fecha_constitucion         date,
    fecha_perfeccionamiento    date,
    perfeccionada              boolean not null default false,
    condicionamientos_abiertos int not null default 0,
    fuente                     text not null,
    aplicativo_origen          text,
    referencia_externa         text,
    atributos                  jsonb not null default '{}'::jsonb,
    version                    int not null default 0,
    created_at                 timestamptz not null default now(),
    updated_at                 timestamptz not null default now()
);
create index garantia_cliente_idx on garantia (cliente_documento);
create index garantia_macroestado_idx on garantia (macroestado);
create index garantia_tipo_idx on garantia (tipo_codigo);
create unique index garantia_referencia_idx on garantia (aplicativo_origen, referencia_externa)
    where referencia_externa is not null;

create table participante_garantia (
    id                uuid primary key,
    garantia_id       uuid not null references garantia (id),
    rol               text not null check (rol in ('PROPIETARIO', 'CONSTITUYENTE', 'GARANTE', 'DEUDOR', 'BENEFICIARIO')),
    tipo_documento    text not null,
    numero_documento  text not null,
    nombre            text not null,
    porcentaje        numeric(5, 2)
);
create index participante_garantia_idx on participante_garantia (garantia_id);

create table vinculo_garantia_obligacion (
    id              uuid primary key,
    garantia_id     uuid not null references garantia (id),
    obligacion_id   uuid not null references obligacion (id),
    tipo            text not null check (tipo in ('ABIERTA', 'CERRADA')),
    tope            numeric(20, 2),
    valor_pactado   numeric(20, 2),
    prioridad       int not null default 100,
    vigente         boolean not null default true,
    created_at      timestamptz not null default now(),
    unique (garantia_id, obligacion_id)
);
create index vinculo_obligacion_idx on vinculo_garantia_obligacion (obligacion_id);

-- Valoraciones: histórico inmutable (RF-0702). Una corrección es una fila nueva.
create table valoracion (
    id               uuid primary key,
    garantia_id      uuid not null references garantia (id),
    tipo             text not null,
    fecha            date not null,
    valor_comercial  numeric(20, 2) not null,
    valor_tecnico    numeric(20, 2),
    moneda           text not null default 'COP',
    perito           text,
    raa              text,
    metodologia      text,
    vigencia_hasta   date,
    soporte_ref      text,
    motivo           text,
    registrado_por   text not null,
    created_at       timestamptz not null default now()
);
create index valoracion_garantia_idx on valoracion (garantia_id, fecha desc);

-- ---------------------------------------------------------------------------
-- Motor de reglas (M14, anexo B): tablas de decisión versionadas.
-- ---------------------------------------------------------------------------
create table regla (
    id           uuid primary key,
    codigo       text not null unique,
    nombre       text not null,
    descripcion  text,
    tipo         text not null,
    created_at   timestamptz not null default now()
);

create table regla_version (
    id               uuid primary key,
    regla_id         uuid not null references regla (id),
    numero           int not null,
    estado           text not null check (estado in ('BORRADOR', 'EN_REVISION', 'APROBADA', 'ACTIVA', 'INACTIVA', 'REEMPLAZADA', 'RECHAZADA')),
    definicion       jsonb not null,
    casos_prueba     jsonb not null default '[]'::jsonb,
    vigente_desde    date,
    vigente_hasta    date,
    creado_por       text not null,
    aprobado_por     text,
    motivo           text,
    hash             text,
    created_at       timestamptz not null default now(),
    enviada_en       timestamptz,
    aprobada_en      timestamptz,
    activada_en      timestamptz,
    unique (regla_id, numero)
);
create unique index regla_version_activa_idx on regla_version (regla_id) where estado = 'ACTIVA';

-- ---------------------------------------------------------------------------
-- Cobertura (M08, anexo A): cada cálculo es inmutable y reproducible.
-- ---------------------------------------------------------------------------
create table calculo_cobertura (
    id                uuid primary key,
    fecha_corte       timestamptz not null,
    alcance           text not null,
    disparador        text not null,
    correlation_id    text,
    estado            text not null,
    hash_entradas     text not null,
    hash_resultado    text not null,
    reglas            jsonb not null,
    entradas          jsonb not null,
    resultado         jsonb not null,
    obligaciones      uuid[] not null default '{}',
    garantias         uuid[] not null default '{}',
    creado_por        text not null,
    created_at        timestamptz not null default now()
);
create index calculo_cobertura_fecha_idx on calculo_cobertura (fecha_corte desc);
create index calculo_cobertura_obligaciones_idx on calculo_cobertura using gin (obligaciones);
create index calculo_cobertura_garantias_idx on calculo_cobertura using gin (garantias);

create table cobertura_vigente (
    obligacion_id        uuid primary key references obligacion (id),
    calculo_id           uuid not null references calculo_cobertura (id),
    exposicion           numeric(20, 2) not null,
    cobertura_objetivo   numeric(12, 6) not null,
    requerido            numeric(20, 2) not null,
    asignado             numeric(20, 2) not null,
    asignado_idoneo      numeric(20, 2) not null,
    ratio                numeric(12, 6),
    ratio_idoneo         numeric(12, 6),
    descubierto          numeric(20, 2) not null,
    brecha               numeric(20, 2) not null,
    estado               text not null,
    actualizado_en       timestamptz not null default now()
);

-- Serie mensual del portafolio para el centro de mando (RF-0103).
create table indicador_portafolio (
    periodo                 date primary key,
    exposicion              numeric(22, 2) not null,
    asignado                numeric(22, 2) not null,
    asignado_idoneo         numeric(22, 2) not null,
    valor_garantias         numeric(22, 2) not null,
    garantias_activas       int not null,
    calculado_en            timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- Auditoría inmutable encadenada por hash (M15, RF-1502).
-- ---------------------------------------------------------------------------
create table auditoria (
    id               bigserial primary key,
    ocurrido_en      timestamptz not null,
    usuario          text not null,
    accion           text not null,
    entidad          text not null,
    entidad_id       text not null,
    antes            jsonb,
    despues          jsonb,
    motivo           text,
    origen           text not null,
    correlation_id   text,
    contexto         jsonb,
    hash_anterior    text not null,
    hash             text not null
);
create index auditoria_entidad_idx on auditoria (entidad, entidad_id);
create index auditoria_correlation_idx on auditoria (correlation_id);

create function auditoria_solo_insercion() returns trigger language plpgsql as $$
begin
    raise exception 'La auditoría es inmutable: % no está permitido', tg_op;
end;
$$;
create trigger auditoria_inmutable before update or delete on auditoria
    for each row execute function auditoria_solo_insercion();

-- Cabeza de la cadena: una sola fila, bloqueada al insertar para serializar el encadenamiento.
create table auditoria_cabeza (
    id     int primary key check (id = 1),
    hash   text not null
);
insert into auditoria_cabeza (id, hash) values (1, 'GENESIS');

-- ---------------------------------------------------------------------------
-- Eventos (M21, M09): outbox transaccional, inbox idempotente y linaje.
-- ---------------------------------------------------------------------------
create table evento_outbox (
    id               uuid primary key,
    tipo             text not null,
    version          int not null,
    agregado         text not null,
    agregado_id      text not null,
    correlation_id   text,
    causation_id     text,
    payload          jsonb not null,
    estado           text not null check (estado in ('PENDIENTE', 'PUBLICADO', 'ERROR')),
    intentos         int not null default 0,
    error            text,
    creado_en        timestamptz not null default now(),
    publicado_en     timestamptz
);
create index evento_outbox_pendiente_idx on evento_outbox (creado_en) where estado <> 'PUBLICADO';
create index evento_outbox_correlation_idx on evento_outbox (correlation_id);

create table evento_inbox (
    id               uuid primary key,
    evento_id        text not null unique,
    tipo             text not null,
    origen           text not null,
    correlation_id   text,
    payload          jsonb not null,
    estado           text not null check (estado in ('RECIBIDO', 'PROCESADO', 'ERROR', 'DUPLICADO')),
    error            text,
    recibido_en      timestamptz not null default now(),
    procesado_en     timestamptz
);
create index evento_inbox_correlation_idx on evento_inbox (correlation_id);

create table linaje_evento (
    id            bigserial primary key,
    inbox_id      uuid references evento_inbox (id),
    outbox_id     uuid references evento_outbox (id),
    entidad       text not null,
    entidad_id    text not null,
    accion        text not null,
    creado_en     timestamptz not null default now()
);
create index linaje_inbox_idx on linaje_evento (inbox_id);

-- Idempotencia de las APIs que crean recursos (RF-1701).
create table idempotencia (
    clave          text primary key,
    operacion      text not null,
    hash_peticion  text not null,
    recurso_id     text not null,
    creado_en      timestamptz not null default now()
);
