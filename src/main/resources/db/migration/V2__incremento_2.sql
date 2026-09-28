-- Garantías 360 — incremento 2: configuración de tipos con maker–checker (M16), constitución y
-- registro (M06), carga masiva (M17) y administración de usuarios y perfiles (M24).

-- ---------------------------------------------------------------------------
-- M16. Cada versión de un tipo guarda su comportamiento, su checklist jurídico y su plantilla de
-- actividades de constitución. Flujo: BORRADOR → EN_REVISION → PUBLICADA (o RECHAZADA); al
-- publicar, la versión anterior queda REEMPLAZADA. Quien crea o edita no aprueba (RF-1607).
-- ---------------------------------------------------------------------------
alter table tipo_garantia add column descripcion text;
alter table tipo_garantia add column inactivado_por text;
alter table tipo_garantia add column inactivado_en timestamptz;
alter table tipo_garantia add column motivo_inactivacion text;

alter table tipo_garantia_version drop constraint tipo_garantia_version_estado_check;
alter table tipo_garantia_version add constraint tipo_garantia_version_estado_check
    check (estado in ('BORRADOR', 'EN_REVISION', 'PUBLICADA', 'REEMPLAZADA', 'RECHAZADA', 'RETIRADA'));

alter table tipo_garantia_version add column comportamiento jsonb;
alter table tipo_garantia_version add column checklist_juridico jsonb not null default '[]'::jsonb;
alter table tipo_garantia_version add column actividades jsonb not null default '[]'::jsonb;
alter table tipo_garantia_version add column motivo text;
alter table tipo_garantia_version add column hash text;
alter table tipo_garantia_version add column enviada_en timestamptz;
alter table tipo_garantia_version add column aprobada_en timestamptz;

update tipo_garantia_version v
set comportamiento = jsonb_build_object(
        'nombre', t.nombre, 'clase', t.clase, 'requiereAvaluo', t.requiere_avaluo,
        'requierePoliza', t.requiere_poliza, 'registroPublico', t.registro_publico,
        'admiteMultiples', t.admite_multiples),
    aprobada_en = v.created_at
from tipo_garantia t
where t.id = v.tipo_id;

alter table tipo_garantia_version alter column comportamiento set not null;

-- Un tipo tiene a lo sumo una versión en edición o en revisión.
create unique index tipo_version_en_curso_idx on tipo_garantia_version (tipo_id)
    where estado in ('BORRADOR', 'EN_REVISION');
create unique index tipo_version_publicada_idx on tipo_garantia_version (tipo_id)
    where estado = 'PUBLICADA';

-- ---------------------------------------------------------------------------
-- M06. Plan de actividades de constitución y perfeccionamiento de cada garantía, generado desde la
-- plantilla de la versión de su tipo. El avance llega por API (Appian) o desde la interfaz.
-- ---------------------------------------------------------------------------
create table actividad_constitucion (
    id                  uuid primary key,
    garantia_id         uuid not null references garantia (id),
    tipo_version_id     uuid not null references tipo_garantia_version (id),
    codigo              text not null,
    nombre              text not null,
    descripcion         text,
    orden               int not null,
    obligatoria         boolean not null,
    requiere_evidencia  boolean not null,
    campos              jsonb not null default '[]'::jsonb,
    rol_responsable     text,
    responsable         text,
    fecha_limite        date,
    estado              text not null check (estado in ('PENDIENTE', 'EN_CURSO', 'COMPLETADA', 'BLOQUEADA', 'NO_APLICA')),
    evidencia_ref       text,
    evidencia_hash      text,
    datos               jsonb not null default '{}'::jsonb,
    observacion         text,
    completada_por      text,
    completada_en       timestamptz,
    actualizado_por     text,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    version             int not null default 0,
    unique (garantia_id, codigo)
);
create index actividad_constitucion_estado_idx on actividad_constitucion (estado);

-- ---------------------------------------------------------------------------
-- M17. Carga masiva: asistente de 4 pasos (plantilla, validación, confirmación, aprobación).
-- El archivo no se guarda; se conserva su hash y el contenido normalizado de cada fila.
-- ---------------------------------------------------------------------------
create table carga_masiva (
    id                      uuid primary key,
    numero                  bigserial unique,
    tipo_codigo             text not null,
    tipo_version_id         uuid not null references tipo_garantia_version (id),
    archivo_nombre          text not null,
    archivo_hash            text not null,
    archivo_bytes           int not null,
    estado                  text not null check (estado in ('VALIDADA', 'CON_ERRORES', 'EN_APROBACION', 'EN_PROCESO',
                                                             'PROCESADA', 'PROCESADA_CON_FALLOS', 'RECHAZADA', 'CANCELADA')),
    total_filas             int not null,
    filas_nuevas            int not null default 0,
    filas_actualizacion     int not null default 0,
    filas_error             int not null default 0,
    filas_procesadas        int not null default 0,
    filas_fallidas          int not null default 0,
    incluir_actualizaciones boolean not null default false,
    creado_por              text not null,
    enviado_por             text,
    aprobado_por            text,
    motivo                  text,
    created_at              timestamptz not null default now(),
    enviada_en              timestamptz,
    decidida_en             timestamptz,
    procesada_en            timestamptz,
    correlation_id          text
);
create index carga_masiva_estado_idx on carga_masiva (estado, created_at desc);

create table carga_masiva_fila (
    id                uuid primary key,
    carga_id          uuid not null references carga_masiva (id),
    numero            int not null,
    accion            text not null check (accion in ('CREAR', 'ACTUALIZAR', 'NINGUNA')),
    estado            text not null check (estado in ('VALIDA', 'ERROR', 'OMITIDA', 'PROCESADA', 'FALLIDA')),
    datos             jsonb not null,
    errores           jsonb not null default '[]'::jsonb,
    garantia_codigo   text,
    procesada_en      timestamptz,
    unique (carga_id, numero)
);

-- ---------------------------------------------------------------------------
-- M24. Usuarios y perfiles. Un perfil agrupa roles de la sección 4 (área × nivel). Cuando el
-- usuario está registrado aquí, sus roles efectivos son los de sus perfiles activos.
-- ---------------------------------------------------------------------------
create table perfil (
    codigo           text primary key,
    nombre           text not null,
    descripcion      text,
    roles            text[] not null,
    activo           boolean not null default true,
    sistema          boolean not null default false,
    creado_por       text not null,
    actualizado_por  text,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    version          int not null default 0
);

create table usuario (
    usuario          text primary key,
    nombre           text not null,
    cargo            text,
    correo           text,
    area             text,
    perfiles         text[] not null default '{}',
    activo           boolean not null default true,
    creado_por       text not null,
    actualizado_por  text,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    version          int not null default 0
);

insert into perfil (codigo, nombre, descripcion, roles, sistema, creado_por) values
    ('OPERACIONES_GESTOR', 'Gestor de operaciones', 'Registro, constitución, valoraciones, pólizas y liberación', '{OPERACIONES_GESTOR}', true, 'sistema'),
    ('OPERACIONES_DIRECTOR', 'Director de operaciones', 'Aprueba liberaciones, cambios de valor y cargas masivas', '{OPERACIONES_DIRECTOR}', true, 'sistema'),
    ('JURIDICA_GESTOR', 'Abogado de garantías', 'Estudio jurídico, hallazgos y condicionamientos', '{JURIDICA_GESTOR}', true, 'sistema'),
    ('JURIDICA_DIRECTOR', 'Director jurídico', 'Aprueba el concepto jurídico final y el inicio de la ejecución', '{JURIDICA_DIRECTOR}', true, 'sistema'),
    ('RIESGOS_GESTOR', 'Analista de riesgos', 'Simula cobertura y propone reglas', '{RIESGOS_GESTOR}', true, 'sistema'),
    ('APROBADOR_REGLAS', 'Aprobador de reglas', 'Director de Riesgos: aprueba reglas', '{APROBADOR_REGLAS}', true, 'sistema'),
    ('CUMPLIMIENTO', 'Cumplimiento', 'Controles SFC y brechas', '{CUMPLIMIENTO}', true, 'sistema'),
    ('AUDITOR', 'Auditor', 'Lectura total, sin permisos de escritura', '{AUDITOR}', true, 'sistema'),
    ('CONSULTOR', 'Consultor', 'Consulta de garantías y tableros', '{CONSULTOR}', true, 'sistema'),
    ('COMERCIAL', 'Comercial', 'Consulta de las garantías de sus clientes', '{COMERCIAL}', true, 'sistema'),
    ('ADMIN_FUNCIONAL', 'Administrador funcional', 'Configura tipos de garantía y aprueba la configuración de otro administrador', '{ADMIN_FUNCIONAL}', true, 'sistema'),
    ('ADMIN_SEGURIDAD', 'Administrador de seguridad', 'Administra usuarios y perfiles', '{ADMIN_SEGURIDAD}', true, 'sistema'),
    ('INTEGRACION', 'Integración técnica', 'Cliente técnico: aplicativos, Appian, Flexcube y procesos batch', '{SISTEMA}', true, 'sistema');
