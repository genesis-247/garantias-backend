package co.bancopopular.garantias360.cobertura;

import co.bancopopular.garantias360.auditoria.AuditoriaService;
import co.bancopopular.garantias360.cobertura.motor.ModeloCobertura.*;
import co.bancopopular.garantias360.cobertura.motor.MotorCobertura;
import co.bancopopular.garantias360.comun.Contexto;
import co.bancopopular.garantias360.comun.Errores;
import co.bancopopular.garantias360.comun.Json;
import co.bancopopular.garantias360.configuracion.TipoGarantia;
import co.bancopopular.garantias360.configuracion.TipoGarantiaRepositorio;
import co.bancopopular.garantias360.eventos.OutboxService;
import co.bancopopular.garantias360.garantia.*;
import co.bancopopular.garantias360.obligacion.Obligacion;
import co.bancopopular.garantias360.obligacion.ObligacionRepositorio;
import co.bancopopular.garantias360.reglas.ResolutorReglas;
import co.bancopopular.garantias360.reglas.ResolutorReglas.Instantanea;
import co.bancopopular.garantias360.reglas.motor.ReglaException;
import co.bancopopular.garantias360.reglas.motor.TipoRegla;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orquesta el motor de cobertura (M08): arma las entradas del grupo conectado de garantías y
 * obligaciones, resuelve parámetros con una instantánea de reglas activas, persiste el cálculo
 * inmutable (RF-0804), actualiza la cobertura vigente y publica CoberturaCalculada.
 */
@Service
public class CoberturaService {

    private static final String COP = "COP";

    private final MotorCobertura motor = new MotorCobertura();
    private final Repositorios.Garantias garantias;
    private final Repositorios.Vinculos vinculos;
    private final Repositorios.Valoraciones valoraciones;
    private final ObligacionRepositorio obligaciones;
    private final TipoGarantiaRepositorio tipos;
    private final CoberturaRepositorios.Calculos calculos;
    private final CoberturaRepositorios.Vigentes vigentes;
    private final ResolutorReglas resolutor;
    private final AuditoriaService auditoria;
    private final OutboxService outbox;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final Map<String, BigDecimal> trm;

    public CoberturaService(Repositorios.Garantias garantias, Repositorios.Vinculos vinculos,
                            Repositorios.Valoraciones valoraciones, ObligacionRepositorio obligaciones,
                            TipoGarantiaRepositorio tipos, CoberturaRepositorios.Calculos calculos,
                            CoberturaRepositorios.Vigentes vigentes, ResolutorReglas resolutor,
                            AuditoriaService auditoria, OutboxService outbox, TransactionTemplate tx, JdbcTemplate jdbc,
                            @Value("#{${g360.trm:{USD:'4000',EUR:'4350'}}}") Map<String, String> trm) {
        this.garantias = garantias;
        this.vinculos = vinculos;
        this.valoraciones = valoraciones;
        this.obligaciones = obligaciones;
        this.tipos = tipos;
        this.calculos = calculos;
        this.vigentes = vigentes;
        this.resolutor = resolutor;
        this.auditoria = auditoria;
        this.outbox = outbox;
        this.tx = tx;
        this.jdbc = jdbc;
        this.trm = trm.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> new BigDecimal(e.getValue())));
    }

    public record Grupo(Set<UUID> garantias, Set<UUID> obligaciones) {
    }

    public record Preparado(EntradaCobertura entrada, Map<String, Object> contexto, Instantanea reglas, Grupo grupo) {
    }

    public record ResumenRecalculo(int calculos, int obligaciones, List<UUID> calculoIds) {
    }

    // ------------------------------------------------------------------ recálculo

    public ResumenRecalculo recalcularPorGarantias(Collection<UUID> ids, String disparador) {
        return recalcular(grupos(Set.copyOf(ids), Set.of()), disparador);
    }

    public ResumenRecalculo recalcularPorObligaciones(Collection<UUID> ids, String disparador) {
        return recalcular(grupos(Set.of(), Set.copyOf(ids)), disparador);
    }

    /** Recálculo completo con fecha de corte (RF-0805): un cálculo por grupo conectado. */
    public ResumenRecalculo recalcularPortafolio(String disparador) {
        List<Vinculo> todos = vinculos.findByVigenteTrue();
        ResumenRecalculo resumen = recalcular(componentes(todos), disparador);
        actualizarIndicadorMensual();
        return resumen;
    }

    private ResumenRecalculo recalcular(List<Grupo> grupos, String disparador) {
        Instantanea reglas = resolutor.instantanea();
        List<UUID> ids = new ArrayList<>();
        int obligacionesCalculadas = 0;
        for (Grupo grupo : grupos) {
            UUID id = tx.execute(s -> persistir(preparar(grupo, reglas), disparador));
            if (id != null) {
                ids.add(id);
                obligacionesCalculadas += grupo.obligaciones().size();
            }
        }
        return new ResumenRecalculo(ids.size(), obligacionesCalculadas, ids);
    }

    /** Simulación (RF-0806): mismo cálculo con otra instantánea de reglas, sin persistir nada. */
    @Transactional(readOnly = true)
    public List<ResultadoCobertura> simular(Instantanea reglas) {
        return componentes(vinculos.findByVigenteTrue()).stream()
                .map(g -> motor.calcular(preparar(g, reglas).entrada()))
                .toList();
    }

    /** Reproduce un cálculo pasado con sus entradas guardadas y compara el hash del resultado. */
    @Transactional(readOnly = true)
    public Map<String, Object> reproducir(UUID calculoId) {
        CalculoCobertura c = calculos.findById(calculoId).orElseThrow(() -> Errores.noEncontrado("El cálculo"));
        EntradaCobertura entrada = Json.leer(c.entradas.get("entrada"), EntradaCobertura.class);
        String hashEntradas = Json.hash(entrada);
        ResultadoCobertura resultado = motor.calcular(entrada);
        String hash = Json.hash(resultado);
        return Map.of("calculoId", calculoId, "hashEntradasOriginal", c.hashEntradas, "hashEntradasReconstruidas", hashEntradas,
                "hashResultadoOriginal", c.hashResultado, "hashResultadoReproducido", hash,
                "reproducible", hash.equals(c.hashResultado) && hashEntradas.equals(c.hashEntradas));
    }

    // ------------------------------------------------------------------ grupos conectados

    private List<Grupo> grupos(Set<UUID> semillasGarantias, Set<UUID> semillasObligaciones) {
        Set<UUID> gs = new HashSet<>(semillasGarantias);
        Set<UUID> os = new HashSet<>(semillasObligaciones);
        List<Vinculo> alcanzados = new ArrayList<>();
        Set<UUID> vistos = new HashSet<>();
        boolean cambio = true;
        while (cambio) {
            List<Vinculo> nuevos = new ArrayList<>();
            if (!gs.isEmpty()) {
                nuevos.addAll(vinculos.findByGarantiaIdInAndVigenteTrue(gs));
            }
            if (!os.isEmpty()) {
                nuevos.addAll(vinculos.findByObligacionIdInAndVigenteTrue(os));
            }
            cambio = false;
            for (Vinculo v : nuevos) {
                if (vistos.add(v.id)) {
                    alcanzados.add(v);
                    cambio |= gs.add(v.garantiaId) | os.add(v.obligacionId);
                }
            }
        }
        List<Grupo> resultado = new ArrayList<>(componentes(alcanzados));
        // Obligaciones sin vínculos (p. ej. tras desvincular la última garantía): cobertura 0.
        Set<UUID> cubiertas = resultado.stream().flatMap(g -> g.obligaciones().stream()).collect(Collectors.toSet());
        os.stream().filter(o -> !cubiertas.contains(o)).forEach(o -> resultado.add(new Grupo(Set.of(), Set.of(o))));
        return resultado;
    }

    /** Componentes conexos del grafo garantía–obligación (unión-búsqueda). */
    private static List<Grupo> componentes(List<Vinculo> lista) {
        Map<String, String> padre = new HashMap<>();
        for (Vinculo v : lista) {
            unir(padre, "g" + v.garantiaId, "o" + v.obligacionId);
        }
        Map<String, Grupo> porRaiz = new TreeMap<>();
        for (Vinculo v : lista) {
            Grupo g = porRaiz.computeIfAbsent(raiz(padre, "g" + v.garantiaId), k -> new Grupo(new TreeSet<>(), new TreeSet<>()));
            g.garantias().add(v.garantiaId);
            g.obligaciones().add(v.obligacionId);
        }
        return List.copyOf(porRaiz.values());
    }

    private static void unir(Map<String, String> padre, String a, String b) {
        padre.putIfAbsent(a, a);
        padre.putIfAbsent(b, b);
        String ra = raiz(padre, a);
        String rb = raiz(padre, b);
        if (!ra.equals(rb)) {
            padre.put(ra.compareTo(rb) < 0 ? rb : ra, ra.compareTo(rb) < 0 ? ra : rb);
        }
    }

    private static String raiz(Map<String, String> padre, String x) {
        while (!padre.get(x).equals(x)) {
            padre.put(x, padre.get(padre.get(x)));
            x = padre.get(x);
        }
        return x;
    }

    // ------------------------------------------------------------------ entradas del motor

    Preparado preparar(Grupo grupo, Instantanea reglas) {
        LocalDate hoy = LocalDate.now(ZoneId.of("America/Bogota"));
        Map<UUID, Obligacion> obls = obligaciones.findByIdIn(grupo.obligaciones()).stream()
                .collect(Collectors.toMap(o -> o.id, o -> o));
        Map<UUID, Garantia> gars = garantias.findByIdIn(grupo.garantias()).stream()
                .filter(g -> g.macroestado.respalda())
                .collect(Collectors.toMap(g -> g.id, g -> g));
        Map<String, TipoGarantia> tiposPorCodigo = tipos.findAll().stream().collect(Collectors.toMap(t -> t.codigo, t -> t));
        List<Vinculo> vs = vinculos.findByGarantiaIdInAndVigenteTrue(grupo.garantias()).stream()
                .filter(v -> gars.containsKey(v.garantiaId) && obls.containsKey(v.obligacionId))
                .sorted(Comparator.comparing((Vinculo v) -> v.garantiaId).thenComparing(v -> v.obligacionId))
                .toList();

        List<ObligacionEntrada> entradasO = obls.values().stream()
                .sorted(Comparator.comparing(o -> o.id))
                .map(o -> obligacionEntrada(o, vs, reglas))
                .toList();
        List<GarantiaEntrada> entradasG = gars.values().stream()
                .sorted(Comparator.comparing(g -> g.id))
                .map(g -> garantiaEntrada(g, tiposPorCodigo.get(g.tipoCodigo), vs, obls, reglas, hoy))
                .toList();
        List<VinculoEntrada> entradasV = vs.stream()
                .map(v -> new VinculoEntrada(v.garantiaId.toString(), v.obligacionId.toString(), v.tope, v.valorPactado, v.prioridad))
                .toList();

        Map<String, Object> contexto = new TreeMap<>();
        contexto.put("fechaCorte", hoy.toString());
        contexto.put("trm", new TreeMap<>(trm));
        return new Preparado(new EntradaCobertura(entradasG, entradasO, entradasV), contexto, reglas, grupo);
    }

    private ObligacionEntrada obligacionEntrada(Obligacion o, List<Vinculo> vs, Instantanea reglas) {
        BigDecimal objetivo = null;
        String reglaObjetivo;
        try {
            var r = reglas.resolver(TipoRegla.COBERTURA_OBJETIVO, Map.of("producto", o.producto, "segmento", o.segmento));
            objetivo = new BigDecimal(r.valor());
            reglaObjetivo = r.regla();
        } catch (ReglaException e) {
            reglaObjetivo = "ERROR: " + e.getMessage();
        }
        int prioridad = vs.stream().filter(v -> v.obligacionId.equals(o.id)).mapToInt(v -> v.prioridad).min().orElse(100);
        BigDecimal exposicion = "CANCELADA".equals(o.estado) ? BigDecimal.ZERO : o.exposicion();
        return new ObligacionEntrada(o.id.toString(), o.numero, exposicion, objetivo, reglaObjetivo, prioridad,
                o.fechaDesembolso);
    }

    private GarantiaEntrada garantiaEntrada(Garantia g, TipoGarantia tipo, List<Vinculo> vs, Map<UUID, Obligacion> obls,
                                            Instantanea reglas, LocalDate hoy) {
        String clase = tipo == null ? "OTRA" : tipo.clase;
        String moneda = COP;
        BigDecimal valorBruto;
        if ("FONDO_GARANTIAS".equals(clase)) {
            valorBruto = valorFondo(g, vs, obls, hoy);
        } else if ("PERSONAL".equals(clase)) {
            valorBruto = BigDecimal.ZERO; // pagaré, aval: sin valor de realización propio
        } else {
            Optional<Valoracion> ultima = valoraciones.findFirstByGarantiaIdOrderByFechaDescCreatedAtDesc(g.id);
            valorBruto = ultima.map(v -> v.valorComercial).orElse(null);
            moneda = ultima.map(v -> v.moneda).orElse(g.moneda);
        }
        long meses = g.fechaUltimaValoracion == null ? 0 : ChronoUnit.MONTHS.between(g.fechaUltimaValoracion, hoy);
        boolean vencida = g.fechaProximaValoracion != null && g.fechaProximaValoracion.isBefore(hoy);

        BigDecimal haircut = null;
        String reglaHaircut;
        try {
            var r = reglas.resolver(TipoRegla.HAIRCUT, Map.of("tipo", g.tipoCodigo, "clase", clase, "moneda", moneda,
                    "segmento", g.segmento, "valoracionVencida", vencida, "mesesAntiguedadValoracion", meses));
            haircut = new BigDecimal(r.valor());
            reglaHaircut = r.regla();
        } catch (ReglaException e) {
            reglaHaircut = "ERROR: " + e.getMessage();
        }
        int prioridad = reglas.intentar(TipoRegla.PRIORIDAD_GARANTIA, Map.of("tipo", g.tipoCodigo, "clase", clase))
                .map(r -> Integer.parseInt(r.valor())).orElse(100);
        var metodo = reglas.intentar(TipoRegla.METODO_DISTRIBUCION, Map.of("tipo", g.tipoCodigo, "segmento", g.segmento));
        var excedente = reglas.intentar(TipoRegla.ASIGNAR_EXCEDENTE, Map.of("tipo", g.tipoCodigo, "producto", g.producto));
        String reglaMetodo = metodo.map(ResolutorReglas.Resolucion::regla).orElse("por defecto: SECUENCIAL");
        return new GarantiaEntrada(g.id.toString(), g.codigo, g.tipoCodigo, moneda, valorBruto,
                COP.equals(moneda) ? null : trm.get(moneda), haircut, reglaHaircut, g.gravamenesPrevios,
                "IDONEA".equals(g.idoneidad), prioridad,
                metodo.map(r -> MetodoDistribucion.valueOf(r.valor())).orElse(MetodoDistribucion.SECUENCIAL), reglaMetodo,
                excedente.map(r -> Boolean.parseBoolean(r.valor())).orElse(false));
    }

    /** FNG/FAG: porcentaje certificado × saldo de la obligación garantizada, si el certificado está vigente. */
    private static BigDecimal valorFondo(Garantia g, List<Vinculo> vs, Map<UUID, Obligacion> obls, LocalDate hoy) {
        JsonNode a = g.atributos;
        if (a == null || !a.hasNonNull("porcentajeCobertura")) {
            return null;
        }
        if (a.hasNonNull("fechaVencimientoCertificado")
                && LocalDate.parse(a.get("fechaVencimientoCertificado").asText()).isBefore(hoy)) {
            return BigDecimal.ZERO;
        }
        BigDecimal saldo = vs.stream().filter(v -> v.garantiaId.equals(g.id))
                .map(v -> obls.get(v.obligacionId).exposicion())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return saldo.multiply(a.get("porcentajeCobertura").decimalValue()).movePointLeft(2);
    }

    // ------------------------------------------------------------------ persistencia

    private UUID persistir(Preparado p, String disparador) {
        if (p.entrada().obligaciones().isEmpty()) {
            return null;
        }
        ResultadoCobertura r = motor.calcular(p.entrada());
        CalculoCobertura c = new CalculoCobertura();
        c.id = UUID.randomUUID();
        c.fechaCorte = Instant.now();
        c.alcance = "GRUPO";
        c.disparador = disparador;
        c.correlationId = Contexto.correlationId();
        c.estado = r.estado().name();
        c.hashEntradas = Json.hash(p.entrada());
        c.hashResultado = Json.hash(r);
        c.reglas = Json.arbol(p.reglas().referencias());
        c.entradas = Json.arbol(Map.of("entrada", p.entrada(), "contexto", p.contexto()));
        c.resultado = Json.arbol(r);
        c.obligaciones = p.entrada().obligaciones().stream().map(o -> UUID.fromString(o.id())).toArray(UUID[]::new);
        c.garantias = p.entrada().garantias().stream().map(g -> UUID.fromString(g.id())).toArray(UUID[]::new);
        c.creadoPor = Contexto.usuario();
        calculos.save(c);

        for (ResultadoObligacion o : r.obligaciones()) {
            CoberturaVigente v = vigentes.findById(UUID.fromString(o.id())).orElseGet(CoberturaVigente::new);
            v.obligacionId = UUID.fromString(o.id());
            v.calculoId = c.id;
            v.exposicion = o.exposicion();
            v.coberturaObjetivo = o.coberturaObjetivo();
            v.requerido = o.requerido();
            v.asignado = o.asignado();
            v.asignadoIdoneo = o.asignadoIdoneo();
            v.ratio = o.ratio();
            v.ratioIdoneo = o.ratioIdoneo();
            v.descubierto = o.descubierto();
            v.brecha = o.brecha();
            v.estado = o.estado().name();
            v.actualizadoEn = Instant.now();
            vigentes.save(v);
        }
        for (ResultadoGarantia rg : r.garantias()) {
            garantias.findById(UUID.fromString(rg.id())).ifPresent(g -> {
                if (rg.estado() == EstadoElemento.VALIDA) {
                    g.valorAdmisible = rg.valorAdmisible();
                    g.valorNeto = rg.valorNeto();
                }
            });
        }
        List<Map<String, Object>> resumen = r.obligaciones().stream().map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("obligacion", o.numero());
            m.put("estado", o.estado().name());
            m.put("exposicion", o.exposicion());
            m.put("asignado", o.asignado());
            m.put("ratio", o.ratio());
            m.put("brecha", o.brecha());
            return m;
        }).toList();
        Map<String, Object> evento = Map.of("calculoId", c.id.toString(), "estado", c.estado, "disparador", disparador,
                "hashResultado", c.hashResultado, "obligaciones", resumen);
        outbox.publicar("CoberturaCalculada", "CalculoCobertura", c.id.toString(), evento);
        auditoria.registrar("CALCULAR_COBERTURA", "CalculoCobertura", c.id.toString(), null,
                Map.of("estado", c.estado, "disparador", disparador, "hashEntradas", c.hashEntradas,
                        "hashResultado", c.hashResultado, "reglas", c.reglas, "obligaciones", resumen), null, "MOTOR");
        return c.id;
    }

    /** Serie mensual del centro de mando (RF-0103). */
    public void actualizarIndicadorMensual() {
        LocalDate periodo = LocalDate.now(ZoneId.of("America/Bogota")).withDayOfMonth(1);
        jdbc.update("""
                insert into indicador_portafolio (periodo, exposicion, asignado, asignado_idoneo, valor_garantias, garantias_activas)
                select ?, coalesce(sum(c.exposicion), 0), coalesce(sum(least(c.asignado, c.exposicion)), 0),
                       coalesce(sum(least(c.asignado_idoneo, c.exposicion)), 0),
                       (select coalesce(sum(valor_comercial), 0) from garantia where macroestado in ('ACTIVA','MONITOREO','ACTUALIZACION','EJECUCION')),
                       (select count(*) from garantia where macroestado in ('ACTIVA','MONITOREO','ACTUALIZACION','EJECUCION'))
                from cobertura_vigente c where c.exposicion > 0
                on conflict (periodo) do update set exposicion = excluded.exposicion, asignado = excluded.asignado,
                    asignado_idoneo = excluded.asignado_idoneo, valor_garantias = excluded.valor_garantias,
                    garantias_activas = excluded.garantias_activas, calculado_en = now()""", periodo);
    }

    // ------------------------------------------------------------------ consultas

    @Transactional(readOnly = true)
    public Optional<CoberturaVigente> vigente(UUID obligacionId) {
        return vigentes.findById(obligacionId);
    }

    @Transactional(readOnly = true)
    public List<CalculoCobertura> historial(UUID obligacionId, int limite) {
        return calculos.deObligacion(obligacionId, limite);
    }

    @Transactional(readOnly = true)
    public CalculoCobertura calculo(UUID id) {
        return calculos.findById(id).orElseThrow(() -> Errores.noEncontrado("El cálculo de cobertura"));
    }
}
