# Anexo C — Garantías sobre cesantías y ahorro en el Fondo Nacional del Ahorro (FNA)
## Garantías 360 — Especificación v0.3

> Resultado de la investigación pedida sobre la decisión E. **No es un concepto jurídico.** Jurídica debe validarlo, en particular los puntos marcados ⚠️.

---

## C.1 Qué es el FNA
El **Fondo Nacional del Ahorro** es una empresa industrial y comercial del Estado, de carácter financiero, vigilada por la SFC y regida por la **Ley 432 de 1998**. Administra dos tipos de ahorro de sus afiliados:
- **Cesantías**, de trabajadores públicos y privados afiliados.
- **Ahorro Voluntario Contractual (AVC)**, dirigido sobre todo a independientes e informales como vía de acceso al crédito del FNA.

Además otorga crédito de vivienda, educativo y **leasing habitacional**, y hace compra de cartera hipotecaria.

---

## C.2 Hallazgos

| # | Hallazgo | Fuente | Impacto en Garantías 360 |
|---|---|---|---|
| H-1 | **La pignoración de cesantías solo procede para créditos de vivienda** y con **autorización expresa y escrita del trabajador** (CST art. 256; Ley 50 de 1990, art. 104). No procede para cualquier crédito ni para educación. | Función Pública, Concepto 14751 de 2015; Actualícese; Acees Abogados | Regla de idoneidad `IDON-FNA` (anexo B) + control SFC (RF-1303) + ítem del checklist jurídico (RF-0504). |
| H-2 | El reglamento de cesantías del FNA permite pignorar las cesantías **a favor de entidades autorizadas por ley**, siempre que el afiliado **no tenga una pignoración vigente con el FNA**. La entidad debe **enviar al FNA copia de la libranza o del pagaré** en la que el afiliado compromete sus cesantías. | Reglamento de cesantías del FNA (Acuerdo 2201 de 2017 y sucesores ⚠️ confirmar el acuerdo vigente; el Acuerdo 2296 de 2020 figura como derogado) | Actividad de constitución "Radicación ante el FNA de la copia de libranza/pagaré" + "Confirmación de pignoración por el FNA" (RF-0603). |
| H-3 | La pignoración se hace efectiva **cuando el afiliado solicita el retiro definitivo** de sus cesantías. | Reglamento de cesantías del FNA ⚠️ | La "ejecución" de esta garantía es un cobro al FNA cuando se produce el retiro definitivo (RF-1101). Hay que monitorear eventos del afiliado (terminación laboral). |
| H-4 | Los créditos de vivienda **del propio FNA** se garantizan con pignoración de cesantías a favor del FNA, y el afiliado con crédito vigente no puede trasladar sus cesantías. | FNA; Infobae | Si el cliente tiene un crédito con el FNA, sus cesantías **no están disponibles** para el Banco (H-2). Es un control de estudio jurídico. |
| H-5 | Para solicitar **compra de cartera** al FNA, las cesantías **no deben estar pignoradas ni embargadas**. | FNA — Compra de cartera | Si un cliente del Banco quiere llevar su crédito al FNA, necesitará la **despignoración**. Esto pasa por el flujo de liberación (RF-1203). |
| H-6 | El **AVC** permite acceder al crédito del FNA, y el FNA puede respaldar sus propias operaciones con pignoración de cesantías. **No se encontró evidencia pública de que el AVC pueda pignorarse a favor de terceros** (otros bancos). | FNA — AVC y reglamento de crédito y leasing | ⚠️ No se habilita "pignoración de AVC a favor del Banco" hasta que Jurídica lo confirme o exista un convenio (P-24). |
| H-7 | La pignoración de cesantías no tiene valor fijo: el saldo crece con la **consignación anual** del empleador (a más tardar el 14 de febrero en el régimen privado; el régimen público tiene reglas propias ⚠️) y con los rendimientos que reconozca el FNA. | Normativa general de cesantías | Valoración = **saldo certificado por el FNA**, actualizado tras la consignación anual (regla `PER-VAL`, 12 meses). |

---

## C.3 Alerta sobre el caso de la visión: "Libranza compra de cartera — respaldo institucional FNA"

El documento de visión incluye como dato demo *"Libranza — Garantía: respaldo institucional FNA — Producto: libranza compra de cartera — Cobertura: 120 %"*.

- **Si "respaldo FNA" significa pignorar cesantías** para una libranza de consumo o de compra de cartera de consumo, **eso no está permitido (H-1)**. La garantía debe marcarse **no idónea**, y la originación del producto debería revisarse con Jurídica.
- **Interpretaciones alternativas que hay que validar con el negocio:**
  1. El **pagador de la libranza es el FNA** (libranza a empleados del FNA): el "respaldo institucional" sería la pagaduría, no una garantía sobre cesantías. En ese caso no es una garantía real y la cobertura del 120 % no se sostiene.
  2. Una **compra de cartera hipotecaria** (vivienda), donde la pignoración sí procede (H-1, H-2).
  3. Un **convenio** específico entre el Banco y el FNA con otro mecanismo de respaldo.

Por ahora, este escenario se modela en las pruebas como **caso de control negativo** (debe resultar no idóneo) hasta aclararlo (P-24).

---

## C.4 Modelo propuesto en Garantías 360

**Tipo de garantía:** `CESANTIAS_FNA` — clase *depósito / derecho económico*; exclusiva de la obligación; sin avalúo; valoración por saldo certificado.

| Campo (M16) | Obligatorio | Nota |
|---|---|---|
| Número de identificación del afiliado | Sí | Llave natural (junto con el FNA). |
| Régimen (público / privado) | Sí | Afecta las fechas de consignación. |
| Empleador | Sí | Para monitorear la terminación laboral. |
| Autorización escrita del trabajador | Sí (documento) | Requisito legal (H-1). |
| Destino del crédito | Sí | Debe ser **VIVIENDA** (regla `IDON-FNA`). |
| Constancia de radicación ante el FNA (copia de libranza o pagaré) | Sí desde *Constitución* | H-2. |
| Confirmación de pignoración del FNA (fecha, número) | Sí para *Perfeccionamiento* | H-2. |
| Saldo certificado y fecha del certificado | Sí | Valoración (H-7). |
| Declaración de no pignoración vigente con el FNA | Sí | H-2, H-4. |

**Ciclo de vida específico:**
- **Estudio jurídico:** autorización escrita válida, destino de vivienda, sin crédito ni pignoración vigente con el FNA.
- **Constitución:** radicación ante el FNA → confirmación del FNA.
- **Valoración:** saldo certificado; actualización anual tras la consignación.
- **Monitoreo:** alerta por terminación laboral o por solicitud de retiro definitivo (si el FNA o el empleador lo informan), certificado vencido o saldo que baja.
- **Ejecución:** solicitud de pago al FNA con cargo a las cesantías pignoradas.
- **Liberación:** comunicación de despignoración al FNA, con constancia.

---

## C.5 Otras garantías sobre ahorro (aclaración)

Las **cuentas de ahorro y los CDT en Banco Popular** pignorados a favor del propio Banco son otro tipo de garantía, el **depósito en garantía**, que cubre el caso de tarjeta de crédito de la visión. Ese tipo **no depende del FNA** y su saldo lo informa el core del Banco.

---

## C.6 Preguntas para Jurídica y Negocio (P-24)

1. ¿Qué productos del Banco usan hoy un "respaldo FNA" y bajo qué figura exacta (pignoración de cesantías, pagaduría, convenio)?
2. ¿Existe un **convenio** firmado entre Banco Popular y el FNA? ¿Qué canal de confirmación y despignoración define (manual, portal, servicio web)?
3. ¿El Banco acepta pignoración del **AVC** a su favor? ¿Hay soporte normativo o contractual (H-6)?
4. ¿Cuál es el **reglamento de cesantías vigente del FNA** que se debe citar (H-2, H-3)?
5. ¿Cómo trata Riesgos esta garantía: idoneidad bajo el Decreto 2555/2010, haircut y PDI?
6. En el sector público (régimen de cesantías anualizado o retroactivo), ¿cambian las reglas de pignoración o de valoración?

---

## C.7 Fuentes consultadas
- [Función Pública — Concepto 14751 de 2015 (pignoración de cesantías)](https://www.funcionpublica.gov.co/eva/gestornormativo/norma.php?i=62368)
- [Actualícese — Pignoración de cesantías, el trabajador debe autorizarlo](https://actualicese.com/archivo/pignoracion-de-cesantias-trabajador-debe-autorizarlo/)
- [Acees Abogados — Pignoración de las cesantías](https://aceesabogados.com/2023/02/13/pignoracion-de-las-cesantias/)
- [Protección — ¿Qué es la pignoración de cesantías?](https://www.proteccion.com/contenidos/persona/cesantias/que-es-pignoracion-cesantias)
- [FNA — Acuerdo 2201 de 2017](https://www.fna.gov.co/sobre-el-fna/normatividad/Ahorros%20Acuerdos/Acuerdo%20%202201%20de%20%202017.pdf)
- [FNA — Acuerdo 2296 de 2020, reglamento de cesantías (derogado)](https://www.fna.gov.co/sobre-el-fna/normatividad/acuerdos%20derogados/ACUERDO%202296%20DE%202020%20REGLAMENTO%20DE%20CESANTIAS.pdf)
- [SUIT — Reglamento de cesantías FNA (ID-RP-003)](https://tramites1.suit.gov.co/registro-web/suit_descargar_archivo?A=12252)
- [FNA — Compra de cartera](https://www.fna.gov.co/vivienda/compra-de-cartera)
- [FNA — Crédito por cesantías](https://www.fna.gov.co/vivienda/credito-por-cesantias)
- [FNA — AVC, alternativa para independientes](https://www.fna.gov.co/prensa/boletines-de-prensa/ahorro-voluntario-alternativa-independientes)
- [FNA — Reglamento de crédito y leasing habitacional](https://www.fna.gov.co/sobre-el-fna/normatividad/Vivienda%20Acuerdos/Reglamento%20de%20Cr%C3%A9dito%20y%20Leasing%20Habitacional%20FNA.pdf)
- [FNA — Libranza, nuevo servicio del FNA](https://www.fna.gov.co/prensa/boletines-de-prensa/libranza-el-nuevo-servicio-del-fondo-nacional-del-ahorro)
- [Infobae — Cuánto puede prestar el FNA a afiliados con cesantías (2026)](https://www.infobae.com/colombia/2026/02/19/fondo-nacional-del-ahorro-cuanto-puede-prestar-a-afiliados-con-cesantias-y-cuales-son-los-beneficios-para-comprar-vivienda-en-2026/)

> Nota: los documentos oficiales del FNA no pudieron abrirse directamente desde el entorno de trabajo, por restricción de red. Los hallazgos H-2 y H-3 provienen de los extractos indexados de esos documentos y deben confirmarse leyendo el acuerdo vigente.
