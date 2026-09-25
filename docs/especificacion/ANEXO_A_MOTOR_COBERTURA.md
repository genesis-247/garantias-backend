# Anexo A — Motor de cobertura
## Garantías 360 — Especificación v0.3

> Detalla M08 (RF-0801 a RF-0807). Los parámetros (haircuts, cobertura objetivo, método de distribución, prioridades) **no están fijos en el código**: salen del motor de reglas (anexo B). Los valores de los ejemplos son **ilustrativos**; los reales los define Riesgo de Crédito (P-09).

---

## A.1 Definiciones y fórmulas

Todo cálculo se hace a una **fecha de corte T** y en **COP**. Los montos en otra moneda se convierten con la TRM de T. Si falta la TRM, la garantía queda *incompleta*.

**Por garantía *g***

| Símbolo | Nombre | Fórmula / origen |
|---|---|---|
| VB_g | Valor bruto | Valor de la valoración vigente a T según el tipo: avalúo comercial, Fasecolda, saldo certificado (depósito, cesantías FNA), valor del contrato (derechos de cobro), porcentaje certificado × saldo (FNG). |
| h_g | Haircut | Regla `HAIRCUT` (puede sumar componentes: tipo, antigüedad del avalúo, moneda). Debe estar en [0, 1]. |
| VA_g | Valor admisible | VB_g × (1 − h_g) |
| GP_g | Gravámenes de mayor prelación | Σ de hipotecas de grado superior y valor comprometido con otros acreedores. |
| VN_g | Valor neto | max(0, VA_g − GP_g) |
| U_g | Valor utilizado | Σ de lo asignado desde *g* a todas sus obligaciones. |
| D_g | Valor disponible | VN_g − U_g |

**Por obligación *o***

| Símbolo | Nombre | Fórmula / origen |
|---|---|---|
| E_o | Exposición | Componentes del saldo en Flexcube a T (regla `EXPOSICION`: capital, más intereses, más otros). Se guarda el snapshot. |
| C*_o | Cobertura objetivo | Regla `COBERTURA_OBJETIVO` (por producto, segmento, tipo de garantía). |
| R_o | Requerido | E_o × C*_o |
| A_o | Valor asignado | Σ de lo asignado a *o* desde todas sus garantías. |
| CR_o | Cobertura real (ratio) | A_o ÷ E_o (si E_o > 0) |
| CI_o | Cobertura idónea | Σ asignado desde garantías **idóneas** ÷ E_o |
| DESC_o | Descubierto | max(0, E_o − A_o) |
| BR_o | Brecha frente al objetivo | max(0, R_o − A_o) |

**Por vínculo *v = (g, o)***: tipo (cerrada o abierta), tope T_v (∞ si no tiene), valor o porcentaje pactado P_v (opcional), prioridad p_v.

---

## A.2 Algoritmo de distribución

El cálculo es determinista: con las mismas entradas y las mismas versiones de reglas, produce el mismo resultado. Cada paso escribe una línea de **traza**.

1. **Preparación**
   - Carga el snapshot de entradas (garantías, vínculos, exposiciones, TRM, versiones de reglas) y calcula su hash SHA-256.
   - Valida cada entrada (A.4). Las garantías inválidas o incompletas se excluyen con su motivo, y las obligaciones afectadas quedan marcadas con cálculo *parcial*.
   - Calcula VB, h, VA, GP y VN de cada garantía, y E, C* y R de cada obligación.
2. **Fase 1 — Asignaciones pactadas.** Para cada vínculo con P_v:
   - a_v = min(P_v, T_v, D_g, R_o − A_o).
3. **Fase 2 — Garantías exclusivas** (vinculadas a una sola obligación). En el orden de la regla `PRIORIDAD_GARANTIA` (por defecto: idóneas antes que no idóneas, después la más líquida primero, después el id):
   - a_v = min(D_g, T_v, R_o − A_o).
4. **Fase 3 — Garantías compartidas**, con el método de la regla `METODO_DISTRIBUCION`:
   - **Secuencial por prioridad (por defecto).**
     - Las obligaciones se ordenan por la regla `PRIORIDAD_OBLIGACION` (por defecto: prioridad del vínculo, después fecha de desembolso, después id).
     - Las garantías de cada obligación se ordenan por `PRIORIDAD_GARANTIA`.
     - a_v = min(D_g, T_v − a_v, R_o − A_o).
   - **Prorrata por necesidad.**
     - Cada garantía compartida reparte D_g entre sus obligaciones en proporción a la necesidad restante N_o = R_o − A_o, sin superar T_v.
     - Si al aplicar topes o necesidades sobra valor, se reparte de nuevo entre las obligaciones que todavía tienen necesidad (llenado iterativo) hasta que no quede necesidad o no quede disponible.
5. **Fase 4 — Excedente** (solo si la regla `ASIGNAR_EXCEDENTE` = sí para el tipo o producto):
   - El disponible restante de una garantía **exclusiva** se asigna a su única obligación, aunque supere el objetivo.
   - Así se reflejan coberturas mayores al 100 %, por ejemplo un depósito en garantía mayor que la deuda.
   - Las garantías compartidas no asignan excedente, para no bloquear respaldo que otras obligaciones podrían usar.
6. **Redondeo** (A.3).
7. **Resultados** (A.1), estado del cálculo (*completo*, *parcial* o *inválido*) y persistencia:
   - El cálculo se guarda en `CALCULO_COBERTURA`, `ASIGNACION_COBERTURA` y `PASO_TRAZA`.
   - El snapshot va a Blob inmutable con su hash.
   - Se publica `CoberturaCalculada`.

**Recálculo** (RF-0805):
- **Incremental**, sobre el grupo conectado de garantías y obligaciones afectado por el evento (componente conexo del grafo de vínculos).
- **Completo nocturno**, a la fecha de corte.

---

## A.3 Redondeo y aritmética

- Aritmética decimal exacta (`BigDecimal`), con escala interna de 10 y modo **HALF_EVEN**. Nunca se usa punto flotante.
- Montos persistidos: 0 decimales en COP y 2 en USD o EUR, redondeados **al final de cada asignación**.
- Cuando un reparto (prorrata) produce fracciones, se usa el **método del mayor residuo**: las unidades sobrantes se asignan a las obligaciones con mayor parte fraccionaria (desempate por id). Así la suma repartida es exactamente igual al total distribuido.
- Los ratios se guardan con 6 decimales y se presentan como porcentaje con 2 decimales.
- La presentación nunca cambia el valor guardado.

---

## A.4 Casos límite y reglas de validación

| Caso | Tratamiento |
|---|---|
| E_o = 0 (obligación sin saldo) | No recibe asignación. CR_o = **"N/A — sin exposición"** (no se divide por cero). No cuenta en los ratios agregados. Dispara la verificación de liberación (M12). |
| E_o < 0 (saldo a favor) | Dato inválido: se trata como sin exposición + alerta de calidad de datos con Flexcube. |
| VB_g = 0 | Válido: VN_g = 0, no aporta. Ejemplo: FNG vencida o saldo de depósito en cero. |
| VB_g ausente o sin valoración | Garantía *incompleta*: se excluye, la obligación queda con cálculo *parcial* y se genera una alerta. |
| Valoración vencida | Según la regla: haircut adicional por antigüedad o exclusión (no idónea). |
| h_g fuera de [0, 1] o regla con error | **No se corrige en silencio.** La garantía queda *inválida* en ese cálculo, se genera una alerta crítica y la traza indica la regla y la versión que fallaron. |
| GP_g > VA_g | VN_g = 0 (nunca negativo). |
| Tope T_v = 0 | El vínculo no recibe asignación (válido). |
| C*_o = 0 | R_o = 0: solo recibe valor por excedente de garantías exclusivas. |
| Varias garantías para una obligación | Se asignan en el orden de `PRIORIDAD_GARANTIA` hasta cubrir R_o. |
| Una garantía para varias obligaciones | Fase 3 según el método. |
| Moneda sin TRM | Garantía u obligación *incompleta*. |
| FNG | VB = porcentaje certificado × saldo de la obligación garantizada, solo con certificado vigente. Es siempre exclusiva de esa obligación. |
| Cesantías FNA fuera de destino de vivienda | La regla de idoneidad la marca **no idónea** y genera una alerta crítica (anexo C). |
| Cambio de regla | Nuevo cálculo con la versión nueva. Los cálculos anteriores quedan inmutables y reproducibles con su versión. |

---

## A.5 Ejemplo resuelto (ilustrativo)

**Garantías**
- **G1 — Inmueble**, compartido (abierta; vínculos con O1 y O2): VB = 500.000.000; h = 30 % → VA = 350.000.000; hipoteca de primer grado a favor de otro acreedor por 50.000.000 → **VN = 300.000.000**.
- **G2 — CDT**, exclusiva de O2: VB = 80.000.000; h = 0 % → **VN = 80.000.000**.

**Obligaciones**
- **O1:** E = 200.000.000; C* = 125 % → R = 250.000.000 (desembolsada primero).
- **O2:** E = 150.000.000; C* = 100 % → R = 150.000.000.

**Método secuencial**
| Paso | Operación | Resultado |
|---|---|---|
| Fase 2 | G2 → O2: min(80, ∞, 150 − 0) | a = 80.000.000; O2 necesita 70.000.000 |
| Fase 3 | O1 (primera): G1 → O1: min(300, ∞, 250) | a = 250.000.000; D_G1 = 50.000.000 |
| Fase 3 | O2: G1 → O2: min(50, ∞, 70) | a = 50.000.000; D_G1 = 0 |
| Resultado O1 | CR = 250 / 200 | **125,00 %** (objetivo cumplido) |
| Resultado O2 | CR = 130 / 150 | **86,67 %**; descubierto 20.000.000; brecha 20.000.000 |

**Método prorrata (mismos datos)**
| Paso | Operación | Resultado |
|---|---|---|
| Fase 2 | G2 → O2 | a = 80.000.000; N_O2 = 70.000.000 |
| Fase 3 | G1 reparte 300 en proporción a N_O1 = 250 y N_O2 = 70 | O1 ← 234.375.000; O2 ← 65.625.000 |
| Resultado | O1: 234,375 / 200; O2: 145,625 / 150 | **117,19 %** y **97,08 %** |

**Lectura:** el método de distribución es una **decisión de política** con impacto directo en las coberturas. Por eso es una regla versionada, que se simula antes de aprobarse (anexo B).

**Traza que ve el usuario para "¿Por qué O2 tiene 86,67 %?"**
1. Exposición de O2 = 150.000.000 (Flexcube, snapshot del 2026-09-24 23:00, hash `9f2c…`).
2. Cobertura objetivo = 100 % (regla `COB-OBJ-TC` v3) → requerido 150.000.000.
3. G2 (CDT, exclusiva): VB 80.000.000, haircut 0 % (regla `HC-DEP` v2) → asigna 80.000.000.
4. G1 (inmueble, compartida): VN 300.000.000 = 500.000.000 × (1 − 30 %) (regla `HC-INM` v5) − 50.000.000 (gravamen de primer grado).
5. Método secuencial (regla `DIST-01` v1): O1 tiene prioridad (desembolso 2025-03-10) y toma 250.000.000; quedan 50.000.000 para O2.
6. Asignado a O2 = 130.000.000 → 130 / 150 = 86,67 %; brecha 20.000.000.

---

## A.6 Casos de prueba mínimos

| ID | Caso | Resultado esperado |
|---|---|---|
| CT-01 | Obligación con E = 0 | CR = N/A, sin asignación, verificación de liberación disparada. |
| CT-02 | Garantía con VB = 0 | VN = 0; cobertura 0 %; cálculo *completo*. |
| CT-03 | VB ausente | Garantía excluida; obligación con cálculo *parcial*; alerta. |
| CT-04 | Haircut 0 % y 100 % (límites) | VA = VB y VA = 0, respectivamente. |
| CT-05 | Haircut −5 % o 120 % | Garantía *inválida*, alerta crítica, sin corrección silenciosa. |
| CT-06 | Cobertura exacta de 100 % | CR = 100,00 %, brecha 0. |
| CT-07 | Cobertura de 0 % (sin garantías) | CR = 0 %, descubierto = E. |
| CT-08 | Cobertura > 100 % con excedente activo (depósito 110 %) | CR = 110,00 %. |
| CT-09 | Cobertura > 100 % con excedente inactivo | CR = C*, disponible remanente visible en la garantía. |
| CT-10 | Garantía compartida, secuencial | Coincide con A.5. |
| CT-11 | Garantía compartida, prorrata | Coincide con A.5; suma exacta tras el redondeo. |
| CT-12 | Prorrata con residuos (p. ej., 100 entre 3) | 34 / 33 / 33 por mayor residuo; suma = 100. |
| CT-13 | Tope de vínculo menor que la necesidad | Asignación = tope; el resto se reasigna. |
| CT-14 | Gravamen mayor que VA | VN = 0. |
| CT-15 | Moneda USD con TRM | Conversión correcta; sin TRM → *incompleta*. |
| CT-16 | FNG 70 % | VB = 70 % × saldo; certificado vencido → 0 y alerta. |
| CT-17 | Cambio de versión de regla | Nuevo cálculo con la nueva versión; el cálculo anterior se reproduce idéntico con la versión anterior. |
| CT-18 | Regresión (golden) | 100 % de los cálculos de referencia de la versión N reproducidos en N+1 cuando las reglas no cambian. |
| CT-19 | Propiedades (aleatorio) | Σ asignado ≤ VN por garantía; ningún valor negativo; determinismo (misma entrada → mismo hash de salida). |
| CT-20 | Datos inválidos (exposición negativa, fecha futura, moneda desconocida) | Rechazo con motivo en la traza; alerta de calidad de datos. |
| CT-21 | Cesantías FNA con destino diferente a vivienda | No idónea; alerta crítica. |
| CT-22 | Rendimiento | Obligación individual p95 < 500 ms; portafolio completo < 2 h. |
