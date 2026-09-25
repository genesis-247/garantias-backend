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
 * Flujo de punta a punta contra PostgreSQL real (sin mocks de base de datos): registro por API,
 * estudio jurídico, constitución, perfeccionamiento, desembolso por evento de Flexcube, cobertura
 * reproducible, maker–checker de reglas e integridad de la auditoría.
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
        }
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
        // Perfeccionar exige escritura y notaría (obligatorias desde PERFECCIONAMIENTO).
        mvc.perform(como(put("/api/v1/garantias/" + codigo + "/perfeccionamiento"), "op", "OPERACIONES_GESTOR")
                        .content("{\"fechaConstitucion\":\"2026-01-10\",\"fechaPerfeccionamiento\":\"2026-01-20\"}"))
                .andExpect(status().isUnprocessableEntity());
        ok(mvc.perform(como(put("/api/v1/garantias/" + codigo + "/perfeccionamiento"), "op", "OPERACIONES_GESTOR")
                .content("{\"fechaConstitucion\":\"2026-01-10\",\"fechaPerfeccionamiento\":\"2026-01-20\","
                        + "\"datosRegistro\":{\"numeroEscritura\":\"1234\",\"notaria\":\"Notaría 5\"}}")));

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
    void eventos_publicados_por_outbox() throws Exception {
        Thread.sleep(1500);
        Integer pendientes = jdbc.queryForObject("select count(*) from evento_outbox where estado <> 'PUBLICADO'", Integer.class);
        assertThat(pendientes).isZero();
        assertThat(jdbc.queryForObject("select count(*) from linaje_evento", Integer.class)).isGreaterThan(0);
        assertThat(UUID.fromString(jdbc.queryForObject("select id::text from calculo_cobertura limit 1", String.class))).isNotNull();
    }
}
