package co.bancopopular.garantias360.demo;

import co.bancopopular.garantias360.cobertura.CoberturaService;
import co.bancopopular.garantias360.comun.Contexto;
import co.bancopopular.garantias360.comun.Json;
import co.bancopopular.garantias360.configuracion.TipoGarantiaService;
import co.bancopopular.garantias360.garantia.GarantiaDtos.*;
import co.bancopopular.garantias360.garantia.GarantiaService;
import co.bancopopular.garantias360.garantia.Macroestado;
import co.bancopopular.garantias360.garantia.Repositorios;
import co.bancopopular.garantias360.integracion.FlexcubeService;
import co.bancopopular.garantias360.reglas.ReglaService;
import co.bancopopular.garantias360.reglas.ReglaVersion;
import co.bancopopular.garantias360.seguridad.Roles;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Supplier;

/**
 * Datos SINTÉTICOS para UAT y demostración (§15 de la especificación; Ley 1581: nunca datos reales).
 * Solo en el perfil "demo" y solo si la base está vacía. Todo pasa por los servicios de negocio,
 * así que también genera auditoría, eventos, linaje y cálculos de cobertura reales.
 */
@Component
@Profile("demo")
public class DatosDemo implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatosDemo.class);
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    private final TipoGarantiaService tipos;
    private final ReglaService reglas;
    private final GarantiaService garantias;
    private final FlexcubeService flexcube;
    private final CoberturaService cobertura;
    private final Repositorios.Garantias repo;
    private final JdbcTemplate jdbc;
    private final int cantidad;
    private final Random rnd = new Random(360);
    private final LocalDate hoy = LocalDate.now(BOGOTA);
    private int secuencia = 1000;

    public DatosDemo(TipoGarantiaService tipos, ReglaService reglas, GarantiaService garantias, FlexcubeService flexcube,
                     CoberturaService cobertura, Repositorios.Garantias repo, JdbcTemplate jdbc,
                     @Value("${g360.demo.cantidad:140}") int cantidad) {
        this.tipos = tipos;
        this.reglas = reglas;
        this.garantias = garantias;
        this.flexcube = flexcube;
        this.cobertura = cobertura;
        this.repo = repo;
        this.jdbc = jdbc;
        this.cantidad = cantidad;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (repo.count() > 0) {
            log.info("Datos demo: la base ya tiene garantías; no se carga nada.");
            return;
        }
        long inicio = System.currentTimeMillis();
        como("admin.funcional", () -> CatalogoDemo.tipos().stream().map(tipos::crear).toList(), Roles.ADMIN_FUNCIONAL);
        cargarReglas();
        casosDeVision();
        for (int i = 0; i < cantidad; i++) {
            generado(i);
        }
        como("motor.nocturno", () -> cobertura.recalcularPortafolio("CARGA_INICIAL_DEMO"), Roles.SISTEMA);
        historicoIndicadores();
        log.info("Datos demo cargados en {} s", (System.currentTimeMillis() - inicio) / 1000);
    }

    // ------------------------------------------------------------------ reglas con maker–checker

    private void cargarReglas() {
        for (var nueva : CatalogoDemo.reglas()) {
            ReglaVersion v = como("riesgos.ana", () -> {
                ReglaVersion creada = reglas.crear(nueva);
                return reglas.enviar(nueva.codigo(), creada.numero);
            }, Roles.RIESGOS_GESTOR);
            como("aprobador.juan", () -> {
                reglas.aprobar(nueva.codigo(), v.numero);
                return reglas.activar(nueva.codigo(), v.numero);
            }, Roles.APROBADOR_REGLAS);
        }
        // Una versión nueva de la regla de haircut queda pendiente de aprobación para mostrar el flujo.
        como("riesgos.ana", () -> {
            ReglaVersion borrador = reglas.nuevaVersionDesde("HC-TIPO", 1);
            return reglas.enviar("HC-TIPO", borrador.numero);
        }, Roles.RIESGOS_GESTOR);
    }

    // ------------------------------------------------------------------ casos del documento de visión

    private void casosDeVision() {
        // Libranza con "respaldo FNA": caso de control negativo (anexo C). Cesantías para un destino distinto de vivienda.
        Caso fna = new Caso("CESANTIAS_FNA", "LIBRANZA", "PERSONAS", "LIBRE_INVERSION", "APP_LIBRANZA",
                cliente("CC", "52890341", "María Fernanda Gómez"), 38_000_000, 45_600_000L, "SALDO_CERTIFICADO");
        fna.atributos.put("numeroAfiliado", "52890341").put("regimen", "PRIVADO").put("empleador", "Colegio Mayor del Rosario (ficticio)")
                .put("autorizacionEscrita", true).put("radicacionFna", "RAD-FNA-2026-00871");
        fna.registro.put("confirmacionFna", "CONF-FNA-99812");
        fna.obligacion("LIB-2026-000871");
        activar(fna);

        // La misma figura, bien usada: cesantías para crédito de vivienda (idónea).
        Caso fnaVivienda = new Caso("CESANTIAS_FNA", "HIPOTECARIO_VIVIENDA", "PERSONAS", "VIVIENDA", "APP_HIPOTECARIO",
                cliente("CC", "80234117", "Jorge Enrique Salcedo"), 21_000_000, 24_500_000L, "SALDO_CERTIFICADO");
        fnaVivienda.atributos.put("numeroAfiliado", "80234117").put("regimen", "PUBLICO").put("empleador", "Gobernación de Boyacá (ficticio)")
                .put("autorizacionEscrita", true).put("radicacionFna", "RAD-FNA-2026-01102");
        fnaVivienda.registro.put("confirmacionFna", "CONF-FNA-10233");
        fnaVivienda.obligacion("HIP-2026-004411");
        activar(fnaVivienda);

        Caso tarjeta = new Caso("DEPOSITO_CDT", "TARJETA_CREDITO", "PERSONAS", "CONSUMO", "APP_TARJETAS",
                cliente("CC", "1020456789", "Carlos Andrés Méndez"), 10_000_000, 11_000_000L, "SALDO_CERTIFICADO");
        tarjeta.atributos.put("numeroTitulo", "CDT-0045521").put("fechaVencimiento", hoy.plusMonths(9).toString());
        tarjeta.obligacion("TC-4509-2231");
        activar(tarjeta);

        Caso fng = new Caso("FNG", "CAPITAL_TRABAJO", "BANCA_EMPRESAS", "CAPITAL_TRABAJO", "APP_EMPRESAS",
                cliente("NIT", "900456123", "Industrias Metálicas Andinas S.A.S."), 850_000_000, null, null);
        fng.atributos.put("numeroCertificado", "FNG-EMP-2026-33120").put("porcentajeCobertura", 70)
                .put("fechaVencimientoCertificado", hoy.plusYears(2).toString()).put("programa", "Empresarial Multipropósito");
        fng.obligacion("CT-2026-000912");
        activar(fng);

        // Apartamento Chicó Reservado: hipoteca empresarial compartida entre dos obligaciones (prorrata).
        Caso chico = new Caso("HIPOTECA_NO_VIVIENDA", "CAPITAL_TRABAJO", "BANCA_EMPRESAS", "CAPITAL_TRABAJO", "APP_EMPRESAS",
                cliente("NIT", "901234567", "Inversiones Altavista S.A.S."), 900_000_000, 2_450_000_000L, "AVALUO_COMERCIAL");
        inmueble(chico, "050C-2211987", "Carrera 7 # 94-60 Apto 1201, Chicó Reservado", "11001");
        chico.obligacion("CT-2026-001104");
        chico.obligacion("LS-2026-000215", 650_000_000);
        activar(chico);

        // Flota logística regional: cinco vehículos que respaldan dos obligaciones; uno con valoración vencida.
        Cliente transportes = cliente("NIT", "860112233", "Transportes del Centro S.A.");
        String[][] flota = {{"TKA", "Kenworth", "T800"}, {"TKB", "Kenworth", "T880"}, {"TKC", "International", "LT625"},
                {"TKD", "Chevrolet", "FVR"}, {"TKE", "Hino", "500 FG"}};
        for (int i = 0; i < flota.length; i++) {
            Caso camion = new Caso("VEHICULO", "VEHICULO", "BANCA_EMPRESAS", "ACTIVO_PRODUCTIVO", "APP_EMPRESAS",
                    transportes, 0, 180_000_000 + i * 35_000_000L, "GUIA_FASECOLDA");
            camion.atributos.put("placa", flota[i][0] + (21 + i) + "L").put("marca", flota[i][1]).put("linea", flota[i][2])
                    .put("modelo", 2021 + i % 3).put("codigoFasecolda", String.format("%08d", 9_100_000 + i)).put("servicio", "CARGA")
                    .put("polizaVigente", true);
            camion.registro.put("folioRgm", "RGM-2025-" + (774100 + i));
            camion.fechaValoracion = i == 4 ? hoy.minusMonths(14) : hoy.minusMonths(2 + i);
            camion.obligacion("LS-2025-000988", i == 0 ? 780_000_000 : 0);
            camion.obligacion("CT-2026-000455", i == 0 ? 240_000_000 : 0);
            activar(camion);
        }

        // Derechos de cobro sobre el contrato con el Hospital San Rafael: condicionada (notificación pendiente).
        Caso derechos = new Caso("DERECHOS_COBRO", "CAPITAL_TRABAJO", "BANCA_EMPRESAS", "CAPITAL_TRABAJO", "APP_EMPRESAS",
                cliente("NIT", "900887766", "Suministros Médicos Andinos S.A.S."), 1_200_000_000, 1_900_000_000L, "VALOR_CONTRATO");
        derechos.atributos.put("contrato", "HSR-2026-SUM-014").put("deudorCedido", "Hospital San Rafael (ficticio)")
                .put("valorContrato", 1_900_000_000).put("fechaTerminacion", hoy.plusMonths(18).toString())
                .put("notificacionDeudorCedido", false);
        derechos.registro.put("folioRgm", "RGM-2026-812334");
        derechos.condicionamientos = 1;
        derechos.obligacion("CT-2026-001377");
        activar(derechos);
    }

    // ------------------------------------------------------------------ portafolio generado

    private static final String[] NOMBRES = {"Ana María", "Luis Fernando", "Diana Carolina", "Juan Sebastián", "Paola Andrea",
            "Andrés Felipe", "Claudia Patricia", "Julián David", "Natalia", "Camilo Ernesto", "Laura Valentina", "Santiago",
            "Martha Lucía", "Óscar Iván", "Juliana", "Ricardo", "Luz Marina", "Felipe Andrés", "Sandra Milena", "Mauricio"};
    private static final String[] APELLIDOS = {"Rodríguez", "Martínez", "García", "López", "Hernández", "González", "Pérez",
            "Sánchez", "Ramírez", "Torres", "Díaz", "Vargas", "Castro", "Rojas", "Moreno", "Jiménez", "Ortiz", "Gutiérrez",
            "Ruiz", "Suárez", "Mejía", "Restrepo", "Ospina", "Cárdenas"};
    private static final String[] EMPRESAS = {"Agroindustrias del Llano", "Constructora Sabana Norte", "Textiles del Valle",
            "Logística Pacífico", "Alimentos La Pradera", "Ferretería Industrial Cafetera", "Servicios Petroleros del Casanare",
            "Laboratorios Andinos", "Comercializadora del Caribe", "Hoteles Sierra Nevada", "Distribuidora Farmacéutica Central",
            "Energía Solar Guajira"};
    private static final String[][] MUNICIPIOS = {{"11001", "Bogotá"}, {"05001", "Medellín"}, {"76001", "Cali"},
            {"08001", "Barranquilla"}, {"68001", "Bucaramanga"}, {"17001", "Manizales"}, {"66001", "Pereira"}, {"15001", "Tunja"}};
    private static final String[][] VEHICULOS = {{"Chevrolet", "Onix"}, {"Renault", "Duster"}, {"Mazda", "CX-30"},
            {"Toyota", "Corolla Cross"}, {"Kia", "Sportage"}, {"Nissan", "Kicks"}, {"Volkswagen", "T-Cross"}};

    private void generado(int i) {
        int perfil = rnd.nextInt(100);
        Caso c;
        if (perfil < 30) {
            long valor = redondear(180_000_000 + rnd.nextInt(700) * 1_000_000L);
            c = new Caso("HIPOTECA_VIVIENDA", "HIPOTECARIO_VIVIENDA", "PERSONAS", "VIVIENDA", "APP_HIPOTECARIO",
                    persona(), (long) (valor * (0.45 + rnd.nextDouble() * 0.3)), valor, "AVALUO_COMERCIAL");
            String[] m = MUNICIPIOS[rnd.nextInt(MUNICIPIOS.length)];
            inmueble(c, String.format("%03d-%d", 50 + rnd.nextInt(300), 100000 + rnd.nextInt(8_000_000)),
                    "Calle " + (1 + rnd.nextInt(150)) + " # " + (1 + rnd.nextInt(90)) + "-" + (1 + rnd.nextInt(99)) + ", " + m[1], m[0]);
            c.atributos.put("polizaVigente", rnd.nextInt(12) != 0);
            c.fechaValoracion = rnd.nextInt(10) == 0 ? hoy.minusMonths(37 + rnd.nextInt(10)) : hoy.minusMonths(rnd.nextInt(34));
            if (rnd.nextInt(8) == 0) {
                c.fechaValoracion = hoy.minusMonths(35).minusDays(rnd.nextInt(20));
            }
        } else if (perfil < 52) {
            long valor = redondear(55_000_000 + rnd.nextInt(160) * 1_000_000L);
            String[] v = VEHICULOS[rnd.nextInt(VEHICULOS.length)];
            c = new Caso("VEHICULO", "VEHICULO", "PERSONAS", "VEHICULO", "APP_VEHICULOS", persona(),
                    (long) (valor * (0.5 + rnd.nextDouble() * 0.35)), valor, "GUIA_FASECOLDA");
            c.atributos.put("placa", placa()).put("marca", v[0]).put("linea", v[1]).put("modelo", 2019 + rnd.nextInt(8))
                    .put("codigoFasecolda", String.format("%08d", 1_000_000 + rnd.nextInt(8_000_000)))
                    .put("servicio", "PARTICULAR").put("polizaVigente", rnd.nextInt(10) != 0);
            c.registro.put("folioRgm", "RGM-" + (hoy.getYear() - rnd.nextInt(3)) + "-" + (100000 + rnd.nextInt(900000)));
            c.fechaValoracion = rnd.nextInt(7) == 0 ? hoy.minusMonths(13 + rnd.nextInt(6)) : hoy.minusMonths(rnd.nextInt(12));
        } else if (perfil < 66) {
            long saldo = redondear(15_000_000 + rnd.nextInt(90) * 1_000_000L);
            c = new Caso("PAGARE", "LIBRANZA", "PERSONAS", "LIBRE_INVERSION", "APP_LIBRANZA", persona(), saldo, null, null);
            c.atributos.put("numeroPagare", "PAG-" + (700000 + i)).put("desmaterializado", true);
        } else if (perfil < 78) {
            long valor = redondear(3_000_000 + rnd.nextInt(60) * 1_000_000L);
            c = new Caso("DEPOSITO_CDT", "TARJETA_CREDITO", "PERSONAS", "CONSUMO", "APP_TARJETAS", persona(),
                    (long) (valor * (0.7 + rnd.nextDouble() * 0.35)), valor, "SALDO_CERTIFICADO");
            c.atributos.put("numeroTitulo", "CDT-" + (1_000_000 + i)).put("fechaVencimiento", hoy.plusMonths(3 + rnd.nextInt(18)).toString());
        } else if (perfil < 88) {
            long saldo = redondear(250_000_000 + rnd.nextInt(1500) * 1_000_000L);
            c = new Caso("FNG", "CAPITAL_TRABAJO", "BANCA_EMPRESAS", "CAPITAL_TRABAJO", "APP_EMPRESAS", empresa(), saldo, null, null);
            c.atributos.put("numeroCertificado", "FNG-EMP-" + hoy.getYear() + "-" + (40000 + i)).put("porcentajeCobertura", 50 + 10 * rnd.nextInt(3))
                    .put("fechaVencimientoCertificado", (rnd.nextInt(15) == 0 ? hoy.minusDays(20) : hoy.plusMonths(6 + rnd.nextInt(30))).toString());
        } else {
            long valor = redondear(900_000_000 + rnd.nextInt(3000) * 1_000_000L);
            c = new Caso("HIPOTECA_NO_VIVIENDA", "CAPITAL_TRABAJO", "BANCA_EMPRESAS", "CAPITAL_TRABAJO", "APP_EMPRESAS", empresa(),
                    (long) (valor * (0.35 + rnd.nextDouble() * 0.4)), valor, "AVALUO_COMERCIAL");
            String[] m = MUNICIPIOS[rnd.nextInt(MUNICIPIOS.length)];
            inmueble(c, String.format("%03d-%d", 50 + rnd.nextInt(300), 100000 + rnd.nextInt(8_000_000)),
                    "Bodega " + (1 + rnd.nextInt(40)) + ", Zona Industrial, " + m[1], m[0]);
            c.atributos.put("polizaVigente", true);
            c.fechaValoracion = hoy.minusMonths(rnd.nextInt(30));
            if (rnd.nextBoolean()) {
                c.obligacion(numeroObligacion("LS"), (long) (valor * 0.25));
            }
        }
        c.obligacion(numeroObligacion(prefijo(c.producto)));
        if (rnd.nextInt(14) == 0) {
            c.condicionamientos = 1 + rnd.nextInt(2);
        }
        int destino = rnd.nextInt(100);
        if (destino < 4) {
            registrarHasta(c, Macroestado.REGISTRO);
        } else if (destino < 8) {
            registrarHasta(c, Macroestado.ESTUDIO_JURIDICO);
        } else if (destino < 11) {
            registrarHasta(c, Macroestado.CONSTITUCION);
        } else {
            String codigo = activar(c);
            if (destino < 15) {
                cancelar(c);
            } else if (destino < 17) {
                cancelar(c);
                como("operaciones.directora", () -> garantias.transicion(codigo,
                        new Transicion(Macroestado.CIERRE, "Paz y salvo emitido, cancelación registrada y documentos entregados", false)),
                        Roles.OPERACIONES_DIRECTOR);
            } else if (destino < 19) {
                mora(c, 120 + rnd.nextInt(200));
                como("juridica.directora", () -> garantias.transicion(codigo,
                        new Transicion(Macroestado.EJECUCION, "Incumplimiento superior a 120 días; se inicia cobro judicial", false)),
                        Roles.JURIDICA_DIRECTOR);
            } else if (destino < 60) {
                como("operaciones.pedro", () -> garantias.transicion(codigo, new Transicion(Macroestado.MONITOREO, null, false)),
                        Roles.OPERACIONES_GESTOR);
            }
        }
    }

    // ------------------------------------------------------------------ flujo de una garantía

    private final class Caso {
        final String tipo;
        final String producto;
        final String segmento;
        final String destino;
        final String aplicativo;
        final Cliente cliente;
        final long saldo;
        final Long valor;
        final String tipoValoracion;
        final ObjectNode atributos = Json.CANONICO.createObjectNode();
        final ObjectNode registro = Json.CANONICO.createObjectNode();
        final List<String> obligaciones = new ArrayList<>();
        final Map<String, Long> saldos = new HashMap<>();
        LocalDate fechaValoracion = hoy.minusMonths(1);
        int condicionamientos;

        Caso(String tipo, String producto, String segmento, String destino, String aplicativo, Cliente cliente, long saldo,
             Long valor, String tipoValoracion) {
            this.tipo = tipo;
            this.producto = producto;
            this.segmento = segmento;
            this.destino = destino;
            this.aplicativo = aplicativo;
            this.cliente = cliente;
            this.saldo = saldo;
            this.valor = valor;
            this.tipoValoracion = tipoValoracion;
        }

        void obligacion(String numero) {
            obligacion(numero, saldo);
        }

        void obligacion(String numero, long saldoObligacion) {
            obligaciones.add(numero);
            saldos.put(numero, saldoObligacion);
        }
    }

    private String registrarHasta(Caso c, Macroestado hasta) {
        String codigo = como("app." + c.aplicativo.toLowerCase(), () -> garantias.registrar(solicitud(c), "demo-" + UUID.randomUUID(),
                "API_PRODUCTO").codigo, Roles.SISTEMA);
        if (hasta == Macroestado.REGISTRO) {
            return codigo;
        }
        boolean condicionada = c.condicionamientos > 0;
        como("juridica.directora", () -> garantias.estudioJuridico(codigo, new EstudioJuridico(
                hasta == Macroestado.ESTUDIO_JURIDICO ? "EN_ESTUDIO" : condicionada ? "CONDICIONADA" : "APROBADA",
                c.condicionamientos, condicionada ? "Aprobada sujeta a condicionamientos" : "Títulos y facultades verificados")),
                Roles.JURIDICA_DIRECTOR);
        if (hasta == Macroestado.ESTUDIO_JURIDICO) {
            return codigo;
        }
        como("operaciones.pedro", () -> garantias.transicion(codigo, new Transicion(Macroestado.CONSTITUCION, null, false)),
                Roles.OPERACIONES_GESTOR);
        if (hasta == Macroestado.CONSTITUCION) {
            return codigo;
        }
        LocalDate constitucion = hoy.minusMonths(1 + rnd.nextInt(30));
        como("operaciones.pedro", () -> garantias.perfeccionar(codigo, new Perfeccionamiento(constitucion,
                constitucion.plusDays(5 + rnd.nextInt(25)), c.registro.isEmpty() ? null : c.registro)), Roles.OPERACIONES_GESTOR);
        return codigo;
    }

    private String activar(Caso c) {
        String codigo = registrarHasta(c, Macroestado.PERFECCIONAMIENTO);
        for (String numero : c.obligaciones) {
            long saldo = c.saldos.get(numero);
            if (saldo > 0) {
                evento("ObligacionDesembolsada", numero, c, saldo, 0);
            }
        }
        if (repo.findByCodigo(codigo).orElseThrow().macroestado == Macroestado.PERFECCIONAMIENTO) {
            // La obligación ya estaba desembolsada (garantía adicional): la activa un gestor.
            como("operaciones.pedro", () -> garantias.transicion(codigo, new Transicion(Macroestado.ACTIVA,
                    "Garantía adicional sobre obligación vigente", false)), Roles.OPERACIONES_GESTOR);
        }
        return codigo;
    }

    private void cancelar(Caso c) {
        c.obligaciones.forEach(n -> evento("ObligacionCancelada", n, c, 0, 0));
    }

    private void mora(Caso c, int dias) {
        c.obligaciones.forEach(n -> evento("MoraActualizada", n, c, c.saldos.get(n) == 0 ? c.saldo : c.saldos.get(n), dias));
    }

    private void evento(String tipo, String numero, Caso c, long saldo, int diasMora) {
        long intereses = Math.round(saldo * 0.012);
        como("flexcube", () -> flexcube.procesar(new FlexcubeService.EventoObligacion("FLX-" + UUID.randomUUID(), tipo, numero,
                c.cliente.numeroDocumento(), c.cliente.nombre(), c.producto, c.segmento, c.destino,
                BigDecimal.valueOf(saldo), BigDecimal.valueOf(intereses), BigDecimal.ZERO, diasMora,
                hoy.minusMonths(1 + rnd.nextInt(24)), Instant.now())), Roles.SISTEMA);
    }

    private RegistroGarantia solicitud(Caso c) {
        List<VinculoSolicitud> vs = c.obligaciones.stream().map(n -> new VinculoSolicitud(n,
                c.obligaciones.size() > 1 ? "ABIERTA" : "CERRADA", null, null, null, c.producto, c.segmento, c.destino)).toList();
        ValoracionSolicitud valoracion = c.valor == null ? null : new ValoracionSolicitud(c.tipoValoracion, c.fechaValoracion,
                BigDecimal.valueOf(c.valor), "AVALUO_COMERCIAL".equals(c.tipoValoracion) ? BigDecimal.valueOf((long) (c.valor * 0.93)) : null,
                "COP", "AVALUO_COMERCIAL".equals(c.tipoValoracion) ? "Avaluadores Asociados (ficticio)" : null,
                "AVALUO_COMERCIAL".equals(c.tipoValoracion) ? "AVAL-" + (10000 + rnd.nextInt(90000)) : null,
                "AVALUO_COMERCIAL".equals(c.tipoValoracion) ? "Comparación de mercado (Res. IGAC 620/2008)" : null,
                null, null, "Valoración inicial");
        List<ParticipanteSolicitud> participantes = List.of(new ParticipanteSolicitud("PROPIETARIO",
                c.cliente.tipoDocumento(), c.cliente.numeroDocumento(), c.cliente.nombre(), new BigDecimal("100")));
        return new RegistroGarantia(c.tipo, new Origen(c.aplicativo, "SOL-" + hoy.getYear() + "-" + (++secuencia)), c.cliente,
                c.producto, c.segmento, "COP", BigDecimal.ZERO, c.atributos, participantes, vs, valoracion);
    }

    private void inmueble(Caso c, String matricula, String direccion, String municipio) {
        c.atributos.put("matriculaInmobiliaria", matricula).put("direccion", direccion).put("municipio", municipio)
                .put("gradoHipoteca", "PRIMER_GRADO").put("polizaVigente", true)
                .put("numeroPredialNacional", String.format("%s%025d", municipio, Math.abs(rnd.nextLong()) % 10_000_000_000_000L));
        c.registro.put("numeroEscritura", String.valueOf(1000 + rnd.nextInt(9000))).put("notaria", "Notaría " + (1 + rnd.nextInt(70)));
    }

    // ------------------------------------------------------------------ utilidades

    private Cliente persona() {
        return cliente("CC", String.valueOf(10_000_000 + rnd.nextInt(1_190_000_000)),
                NOMBRES[rnd.nextInt(NOMBRES.length)] + " " + APELLIDOS[rnd.nextInt(APELLIDOS.length)] + " " + APELLIDOS[rnd.nextInt(APELLIDOS.length)]);
    }

    private Cliente empresa() {
        return cliente("NIT", String.valueOf(900_000_000 + rnd.nextInt(99_999_999)),
                EMPRESAS[rnd.nextInt(EMPRESAS.length)] + " S.A.S.");
    }

    private static Cliente cliente(String tipo, String numero, String nombre) {
        return new Cliente(tipo, numero, nombre);
    }

    private String placa() {
        return "" + (char) ('A' + rnd.nextInt(26)) + (char) ('A' + rnd.nextInt(26)) + (char) ('A' + rnd.nextInt(26))
                + String.format("%03d", rnd.nextInt(1000));
    }

    private String numeroObligacion(String prefijo) {
        return prefijo + "-" + (hoy.getYear() - rnd.nextInt(3)) + "-" + String.format("%06d", ++secuencia);
    }

    private static String prefijo(String producto) {
        return switch (producto) {
            case "HIPOTECARIO_VIVIENDA" -> "HIP";
            case "VEHICULO" -> "VEH";
            case "LIBRANZA" -> "LIB";
            case "TARJETA_CREDITO" -> "TC";
            default -> "CT";
        };
    }

    private static long redondear(long valor) {
        return BigDecimal.valueOf(valor).setScale(-5, RoundingMode.HALF_EVEN).longValue();
    }

    /** Serie de 11 meses anteriores para la gráfica de evolución (solo demo). */
    private void historicoIndicadores() {
        Map<String, Object> actual = jdbc.queryForMap("select * from indicador_portafolio order by periodo desc limit 1");
        BigDecimal exposicion = (BigDecimal) actual.get("exposicion");
        BigDecimal asignado = (BigDecimal) actual.get("asignado");
        BigDecimal idoneo = (BigDecimal) actual.get("asignado_idoneo");
        BigDecimal valor = (BigDecimal) actual.get("valor_garantias");
        int activas = ((Number) actual.get("garantias_activas")).intValue();
        LocalDate periodo = ((java.sql.Date) actual.get("periodo")).toLocalDate();
        for (int m = 1; m <= 11; m++) {
            double f = 1 - m * 0.018;
            double cobertura = 1 - m * 0.006 + (rnd.nextDouble() - 0.5) * 0.01;
            jdbc.update("insert into indicador_portafolio (periodo, exposicion, asignado, asignado_idoneo, valor_garantias, garantias_activas) values (?, ?, ?, ?, ?, ?) on conflict do nothing",
                    periodo.minusMonths(m), escala(exposicion, f), escala(asignado, f * cobertura), escala(idoneo, f * (cobertura - 0.01)),
                    escala(valor, f), (int) (activas * f));
        }
    }

    private static BigDecimal escala(BigDecimal v, double f) {
        return v.multiply(BigDecimal.valueOf(f)).setScale(0, RoundingMode.HALF_EVEN);
    }

    private static <T> T como(String usuario, Supplier<T> accion, String... roles) {
        var anterior = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(usuario, "n/a",
                Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList()));
        try {
            final Object[] r = new Object[1];
            Contexto.conCorrelation(UUID.randomUUID().toString(), () -> r[0] = accion.get());
            @SuppressWarnings("unchecked") T t = (T) r[0];
            return t;
        } finally {
            SecurityContextHolder.getContext().setAuthentication(anterior);
        }
    }
}
