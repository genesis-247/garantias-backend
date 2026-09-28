package co.bancopopular.garantias360.integracion;

import co.bancopopular.garantias360.demo.CatalogoDemo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Flujo de punta a punta contra PostgreSQL real (sin mocks de base de datos): tipos publicados con
 * maker–checker, registro por API, estudio jurídico, plan de constitución, perfeccionamiento,
 * desembolso por evento de Flexcube, cobertura reproducible, maker–checker de reglas, carga masiva,
 * segregación de funciones en perfiles e integridad de la auditoría.
 * Requiere G360_TEST_DB_URL (una base vacía dedicada a pruebas: se limpia al iniciar).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
@EnabledIfEnvironmentVariable(named = "G360_TEST_DB_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlujoGarantiaIT {

    @TestConfiguration
    static class BaseLimpia {
        @Bean
        FlywayMigrationStrategy limpiarYMigrar() {
            return (Flyway f) -> {
                f.clean();
                f.migrate();
            };
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;

    private String codigo;

    private MockHttpServletRequestBuilder como(MockHttpServletRequestBuilder r, String usuario, String roles) {
        return r.header("X-Usuario", usuario).header("X-Roles", roles).contentType("application/json");
    }

    private JsonNode ok(ResultActions r) throws Exception {
        String cuerpo = r.andReturn().getResponse().getContentAsString();
        int estado = r.andReturn().getResponse().getStatus();
        assertThat(estado).as(cuerpo).isBetween(200, 299);
        return cuerpo.isEmpty() ? null : json.readTree(cuerpo);
    }

    @Test
    @Order(1)
    void configuracion_y_reglas_con_maker_checker() throws Exception {
        for (var t : CatalogoDemo.tipos()) {
            ok(mvc.perform(como(post("/api/v1/tipos-garantia"), "admin", "ADMIN_FUNCIONAL").content(json.writeValueAsString(t))));
            ok(mvc.perform(como(post("/api/v1/tipos-garantia/" + t.codigo() + "/versiones/1/envio"), "admin", "ADMIN_FUNCIONAL")));
            // Quien editó la versión no la publica (RF-1607).
            mvc.perform(como(post("/api/v1/tipos-garantia/" + t.codigo() + "/versiones/1/aprobacion"), "admin", "ADMIN_FUNCIONAL"))
                    .andExpect(status().isForbidden());
            ok(mvc.perform(como(post("/api/v1/tipos-garantia/" + t.codigo() + "/versiones/1/aprobacion"), "admin2", "ADMIN_FUNCIONAL")));
        }
        JsonNode esquema = ok(mvc.perform(como(get("/api/v1/tipos-garantia/HIPOTECA_VIVIENDA/esquema"), "app", "SISTEMA")));
        assertThat(esquema.at("/properties/matriculaInmobiliaria/x-llaveNatural").asBoolean()).isTrue();
        assertThat(esquema.at("/properties/numeroEscritura/x-obligatorioDesde").asText()).isEqualTo("PERFECCIONAMIENTO");
        for (var r : CatalogoDemo.reglas()) {
            ok(mvc.perform(como(post("/api/v1/reglas"), "riesgos.ana", "RIESGOS_GESTOR").content(json.writeValueAsString(r))));
            ok(mvc.perform(como(post("/api/v1/reglas/" + r.codigo() + "/versiones/1/envio"), "riesgos.ana", "RIESGOS_GESTOR")));
            // El creador no puede aprobar aunque tenga el rol de aprobador.
            mvc.perform(como(post("/api/v1/reglas/" + r.codigo() + "/versiones/1/aprobacion"), "riesgos.ana", "APROBADOR_REGLAS"))
                    .andExpect(status().isForbidden());
            ok(mvc.perform(como(post("/api/v1/reglas/" + r.codigo() + "/versiones/1/aprobacion"), "juan", "APROBADOR_REGLAS")));
            ok(mvc.perform(como(post("/api/v1/reglas/" + r.codigo() + "/versiones/1/activacion"), "juan", "APROBADOR_REGLAS")));
        }
    }

    @Test
    @Order(2)
    void registro_idempotente_y_ciclo_hasta_activa() throws Exception {
        String cuerpo = """
                {"tipo":"HIPOTECA_VIVIENDA","origen":{"aplicativo":"APP_HIPOTECARIO","referencia":"SOL-IT-1"},
                 "cliente":{"tipoDocumento":"CC","numeroDocumento":"1010","nombre":"Cliente Prueba"},
                 "producto":"HIPOTECARIO_VIVIENDA","segmento":"PERSONAS",
                 "atributos":{"matriculaInmobiliaria":"050-123456","direccion":"Calle 1 # 2-3","municipio":"11001",
                              "gradoHipoteca":"PRIMER_GRADO","polizaVigente":true},
                 "obligaciones":[{"numeroObligacion":"HIP-IT-1","destino":"VIVIENDA"}],
                 "valoracionInicial":{"tipo":"AVALUO_COMERCIAL","fecha":"%s","valorComercial":400000000.00}}"""
                .formatted(LocalDate.now().minusMonths(1));
        JsonNode primero = ok(mvc.perform(como(post("/api/v1/garantias"), "app", "SISTEMA").header("Idempotency-Key", "it-1").content(cuerpo)));
        JsonNode segundo = ok(mvc.perform(como(post("/api/v1/garantias"), "app", "SISTEMA").header("Idempotency-Key", "it-1").content(cuerpo)));
        codigo = primero.get("codigo").asText();
        assertThat(codigo).matches("GAR-\\d{4}-\\d{6}");
        assertThat(segundo.get("codigo").asText()).isEqualTo(codigo);

        // Constitución sin concepto jurídico: rechazada.
        mvc.perform(como(post("/api/v1/garantias/" + codigo + "/transiciones"), "op", "OPERACIONES_GESTOR")
                .content("{\"destino\":\"ESTUDIO_JURIDICO\"}"));
        mvc.perform(como(post("/api/v1/garantias/" + codigo + "/transiciones"), "op", "OPERACIONES_GESTOR")
                .content("{\"destino\":\"CONSTITUCION\"}")).andExpect(status().isConflict());
        // Un gestor jurídico no puede emitir el concepto final.
        mvc.perform(como(put("/api/v1/garantias/" + codigo + "/estudio-juridico"), "abogado", "JURIDICA_GESTOR")
                .content("{\"estado\":\"APROBADA\",\"condicionamientosAbiertos\":0}")).andExpect(status().isForbidden());
        ok(mvc.perform(como(put("/api/v1/garantias/" + codigo + "/estudio-juridico"), "directora", "JURIDICA_DIRECTOR")
                .content("{\"estado\":\"APROBADA\",\"condicionamientosAbiertos\":0}")));
        ok(mvc.perform(como(post("/api/v1/garantias/" + codigo + "/transiciones"), "op", "OPERACIONES_GESTOR")
                .content("{\"destino\":\"CONSTITUCION\"}")));
        // El paso a CONSTITUCION genera el plan desde la plantilla del tipo (RF-0601).
        JsonNode plan = ok(mvc.perform(como(get("/api/v1/garantias/" + codigo + "/actividades-constitucion"), "op", "OPERACIONES_GESTOR")));
        assertThat(plan.get("actividades").size()).isEqualTo(6);
        // RF-0604: no se perfecciona con actividades obligatorias pendientes.
        mvc.perform(como(put("/api/v1/garantias/" + codigo + "/perfeccionamiento"), "op", "OPERACIONES_GESTOR")
                        .content("{\"fechaConstitucion\":\"2026-01-10\",\"fechaPerfeccionamiento\":\"2026-01-20\"}"))
                .andExpect(status().isUnprocessableEntity());
        // Completar sin evidencia no se permite.
        mvc.perform(como(patch("/api/v1/garantias/" + codigo + "/actividades-constitucion/FIRMA_DOCUMENTOS"), "op", "OPERACIONES_GESTOR")
                .content("{\"estado\":\"COMPLETADA\"}")).andExpect(status().isUnprocessableEntity());
        String hash = "a".repeat(64);
        for (JsonNode a : plan.get("actividades")) {
            String datos = "ESCRITURA_PUBLICA".equals(a.get("codigo").asText())
                    ? ",\"datos\":{\"numeroEscritura\":\"1234\",\"notaria\":\"Notaría 5\"}" : "";
            ok(mvc.perform(como(patch("/api/v1/garantias/" + codigo + "/actividades-constitucion/" + a.get("codigo").asText()),
                    "op", "OPERACIONES_GESTOR").content("{\"estado\":\"COMPLETADA\",\"evidenciaRef\":\"OB-1\",\"evidenciaHash\":\""
                    + hash + "\"" + datos + "}")));
        }
        ok(mvc.perform(como(put("/api/v1/garantias/" + codigo + "/perfeccionamiento"), "op", "OPERACIONES_GESTOR")
                .content("{\"fechaConstitucion\":\"2026-01-10\",\"fechaPerfeccionamiento\":\"2026-01-20\"}")));

        String evento = """
                {"eventoId":"FLX-IT-1","tipo":"ObligacionDesembolsada","numeroObligacion":"HIP-IT-1","clienteDocumento":"1010",
                 "clienteNombre":"Cliente Prueba","producto":"HIPOTECARIO_VIVIENDA","segmento":"PERSONAS","destino":"VIVIENDA",
                 "saldoCapital":250000000,"saldoIntereses":0,"saldoOtros":0,"diasMora":0,"ocurridoEn":"%s"}""".formatted(Instant.now());
        assertThat(ok(mvc.perform(como(post("/api/v1/integraciones/flexcube/eventos"), "flexcube", "SISTEMA").content(evento)))
                .get("estado").asText()).isEqualTo("PROCESADO");
        assertThat(ok(mvc.perform(como(post("/api/v1/integraciones/flexcube/eventos"), "flexcube", "SISTEMA").content(evento)))
                .get("estado").asText()).isEqualTo("DUPLICADO");

        JsonNode exp = ok(mvc.perform(como(get("/api/v1/garantias/" + codigo), "consulta", "CONSULTOR")));
        assertThat(exp.at("/garantia/macroestado").asText()).isEqualTo("ACTIVA");
        assertThat(exp.at("/garantia/idoneidad").asText()).isEqualTo("IDONEA");
    }

    @Test
    @Order(3)
    void cobertura_explicable_y_reproducible() throws Exception {
        JsonNode c = ok(mvc.perform(como(get("/api/v1/obligaciones/HIP-IT-1/cobertura"), "consulta", "CONSULTOR")));
        // 400.000.000 × (1 − 0,30) = 280.000.000 frente a una exposición de 250.000.000 y objetivo 100 %.
        assertThat(c.at("/cobertura/asignado").decimalValue()).isEqualByComparingTo("250000000");
        assertThat(c.at("/cobertura/ratio").decimalValue()).isEqualByComparingTo("1");
        assertThat(c.at("/calculo/resultado/traza").size()).isGreaterThan(3);

        String calculoId = c.at("/cobertura/calculoId").asText();
        JsonNode rep = ok(mvc.perform(como(post("/api/v1/calculos-cobertura/" + calculoId + "/reproduccion"), "auditor", "AUDITOR")));
        assertThat(rep.get("reproducible").asBoolean()).as(rep.toString()).isTrue();
    }

    @Test
    @Order(4)
    void liberacion_bloqueada_con_obligaciones_activas_y_valor_con_umbral() throws Exception {
        mvc.perform(como(post("/api/v1/garantias/" + codigo + "/transiciones"), "op", "OPERACIONES_GESTOR")
                .content("{\"destino\":\"LIBERACION\"}")).andExpect(status().isUnprocessableEntity());
        // Un cambio de valor mayor al 10 % no lo puede registrar un gestor.
        mvc.perform(como(post("/api/v1/garantias/" + codigo + "/valoraciones"), "op", "OPERACIONES_GESTOR")
                        .content("{\"tipo\":\"AVALUO_COMERCIAL\",\"fecha\":\"" + LocalDate.now() + "\",\"valorComercial\":300000000,\"motivo\":\"Nuevo avalúo\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(5)
    void auditoria_integra_e_inmutable() throws Exception {
        assertThat(ok(mvc.perform(como(get("/api/v1/auditoria/verificacion"), "auditor", "AUDITOR"))).get("integra").asBoolean()).isTrue();
        assertThatThrownBy(() -> jdbc.update("update auditoria set usuario = 'intruso' where id = (select min(id) from auditoria)"))
                .hasMessageContaining("inmutable");
        assertThatThrownBy(() -> jdbc.update("delete from auditoria")).hasMessageContaining("inmutable");
    }

    @Test
    @Order(6)
    void la_verificacion_detecta_una_alteracion() throws Exception {
        jdbc.execute("alter table auditoria disable trigger auditoria_inmutable");
        try {
            jdbc.update("update auditoria set motivo = 'alterado' where id = (select min(id) + 3 from auditoria)");
            JsonNode v = ok(mvc.perform(como(get("/api/v1/auditoria/verificacion"), "auditor", "AUDITOR")));
            assertThat(v.get("integra").asBoolean()).isFalse();
            assertThat(v.get("primerRegistroAlterado").isNull()).isFalse();
        } finally {
            jdbc.execute("alter table auditoria enable trigger auditoria_inmutable");
        }
    }

    @Test
    @Order(7)
    void carga_masiva_con_maker_checker() throws Exception {
        String hoy = LocalDate.now().toString();
        String csv = "referencia;clienteTipoDocumento;clienteNumeroDocumento;clienteNombre;producto;segmento;numeroTitulo;fechaVencimiento\n"
                + "LOTE-IT-1;CC;2020;Cliente Uno;TARJETA_CREDITO;PERSONAS;CDT-IT-1;" + hoy + "\n"
                + "LOTE-IT-2;CC;2021;Cliente Dos;TARJETA_CREDITO;PERSONAS;CDT-IT-2;" + hoy + "\n"
                + "LOTE-IT-3;CC;2022;=HIPERVINCULO(1);TARJETA_CREDITO;PERSONAS;CDT-IT-1;31/02/2027\n";
        var archivo = new org.springframework.mock.web.MockMultipartFile("archivo", "lote.csv", "text/csv",
                csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JsonNode carga = ok(mvc.perform(multipart("/api/v1/cargas-masivas").file(archivo).param("tipo", "DEPOSITO_CDT")
                .header("X-Usuario", "op").header("X-Roles", "OPERACIONES_GESTOR")));
        assertThat(carga.get("filasNuevas").asInt()).isEqualTo(2);
        assertThat(carga.get("filasError").asInt()).isEqualTo(1);
        String id = carga.get("id").asText();
        // Con errores, el envío exige confirmar que se excluyen.
        mvc.perform(como(post("/api/v1/cargas-masivas/" + id + "/envio"), "op", "OPERACIONES_GESTOR")
                .content("{\"incluirActualizaciones\":false,\"excluirErrores\":false}")).andExpect(status().isUnprocessableEntity());
        ok(mvc.perform(como(post("/api/v1/cargas-masivas/" + id + "/envio"), "op", "OPERACIONES_GESTOR")
                .content("{\"incluirActualizaciones\":false,\"excluirErrores\":true}")));
        mvc.perform(como(post("/api/v1/cargas-masivas/" + id + "/aprobacion"), "op", "OPERACIONES_DIRECTOR"))
                .andExpect(status().isForbidden());
        ok(mvc.perform(como(post("/api/v1/cargas-masivas/" + id + "/aprobacion"), "directora", "OPERACIONES_DIRECTOR")));
        String estado = "";
        for (int i = 0; i < 40 && !estado.startsWith("PROCESADA"); i++) {
            Thread.sleep(250);
            estado = ok(mvc.perform(como(get("/api/v1/cargas-masivas/" + id), "op", "OPERACIONES_GESTOR"))).get("estado").asText();
        }
        assertThat(estado).isEqualTo("PROCESADA");
        assertThat(jdbc.queryForObject("select count(*) from garantia where fuente = 'CARGA_MASIVA'", Integer.class)).isEqualTo(2);
    }

    @Test
    @Order(8)
    void perfiles_con_segregacion_de_funciones() throws Exception {
        mvc.perform(como(post("/api/v1/seguridad/perfiles"), "seg", "ADMIN_SEGURIDAD")
                        .content("{\"codigo\":\"MIXTO\",\"nombre\":\"Mixto\",\"roles\":[\"AUDITOR\",\"OPERACIONES_GESTOR\"]}"))
                .andExpect(status().isUnprocessableEntity());
        ok(mvc.perform(como(post("/api/v1/seguridad/usuarios"), "seg", "ADMIN_SEGURIDAD")
                .content("{\"usuario\":\"u.prueba\",\"nombre\":\"Usuario Prueba\",\"perfiles\":[\"CONSULTOR\"]}")));
        // Un usuario registrado tiene los roles de sus perfiles, no los que diga la cabecera.
        mvc.perform(como(post("/api/v1/garantias/" + codigo + "/transiciones"), "u.prueba", "OPERACIONES_GESTOR")
                .content("{\"destino\":\"MONITOREO\"}")).andExpect(status().isForbidden());
        // Nadie modifica su propio usuario.
        ok(mvc.perform(como(post("/api/v1/seguridad/usuarios"), "seg", "ADMIN_SEGURIDAD")
                .content("{\"usuario\":\"seg.dos\",\"nombre\":\"Seguridad Dos\",\"perfiles\":[\"ADMIN_SEGURIDAD\"]}")));
        mvc.perform(como(put("/api/v1/seguridad/usuarios/seg.dos"), "seg.dos", "ADMIN_SEGURIDAD")
                        .content("{\"nombre\":\"Seguridad Dos\",\"perfiles\":[\"ADMIN_SEGURIDAD\",\"AUDITOR\"]}"))
                .andExpect(status().isForbidden());
        // Un usuario inactivo no entra.
        ok(mvc.perform(como(put("/api/v1/seguridad/usuarios/u.prueba"), "seg", "ADMIN_SEGURIDAD")
                .content("{\"nombre\":\"Usuario Prueba\",\"perfiles\":[\"CONSULTOR\"],\"activo\":false}")));
        mvc.perform(como(get("/api/v1/tablero"), "u.prueba", "CONSULTOR")).andExpect(status().isForbidden());
    }

    @Test
    @Order(9)
    void eventos_publicados_por_outbox() throws Exception {
        Thread.sleep(1500);
        Integer pendientes = jdbc.queryForObject("select count(*) from evento_outbox where estado <> 'PUBLICADO'", Integer.class);
        assertThat(pendientes).isZero();
        assertThat(jdbc.queryForObject("select count(*) from linaje_evento", Integer.class)).isGreaterThan(0);
        assertThat(UUID.fromString(jdbc.queryForObject("select id::text from calculo_cobertura limit 1", String.class))).isNotNull();
    }
}
