package co.bancopopular.garantias360.api;

import co.bancopopular.garantias360.cobertura.CalculoCobertura;
import co.bancopopular.garantias360.cobertura.CoberturaService;
import co.bancopopular.garantias360.comun.Errores;
import co.bancopopular.garantias360.garantia.Repositorios;
import co.bancopopular.garantias360.obligacion.Obligacion;
import co.bancopopular.garantias360.obligacion.ObligacionRepositorio;
import co.bancopopular.garantias360.seguridad.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Cobertura", description = "Cobertura explicable, versionada y reproducible (anexo A)")
public class CoberturaController {

    private final CoberturaService cobertura;
    private final ObligacionRepositorio obligaciones;
    private final Repositorios.Vinculos vinculos;
    private final Repositorios.Garantias garantias;

    public CoberturaController(CoberturaService cobertura, ObligacionRepositorio obligaciones,
                               Repositorios.Vinculos vinculos, Repositorios.Garantias garantias) {
        this.cobertura = cobertura;
        this.obligaciones = obligaciones;
        this.vinculos = vinculos;
        this.garantias = garantias;
    }

    @GetMapping("/obligaciones/{numero}/cobertura")
    @PreAuthorize(Roles.LECTURA)
    @Operation(summary = "Cobertura vigente de una obligación con su cálculo y traza")
    public Map<String, Object> porObligacion(@PathVariable String numero) {
        Obligacion o = obligacion(numero);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("obligacion", o);
        r.put("exposicion", o.exposicion());
        r.put("garantias", vinculos.findByObligacionIdAndVigenteTrue(o.id).stream()
                .map(v -> garantias.findById(v.garantiaId).map(g -> Map.of("codigo", g.codigo, "tipo", g.tipoCodigo,
                        "macroestado", g.macroestado, "idoneidad", g.idoneidad, "vinculo", v)).orElse(null))
                .filter(Objects::nonNull).toList());
        cobertura.vigente(o.id).ifPresent(v -> {
            r.put("cobertura", v);
            r.put("calculo", cobertura.calculo(v.calculoId));
        });
        r.put("historial", cobertura.historial(o.id, 24).stream()
                .map(c -> Map.of("id", c.id, "fechaCorte", c.fechaCorte, "estado", c.estado, "disparador", c.disparador))
                .toList());
        return r;
    }

    @GetMapping("/clientes/{documento}/cobertura")
    @PreAuthorize(Roles.LECTURA)
    @Operation(summary = "Cobertura consolidada del cliente (consumida por FICO y aplicativos)")
    public Map<String, Object> porCliente(@PathVariable String documento) {
        List<Map<String, Object>> filas = obligaciones.findByClienteDocumento(documento).stream().map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("obligacion", o.numero);
            m.put("producto", o.producto);
            m.put("estado", o.estado);
            m.put("exposicion", o.exposicion());
            m.put("cobertura", cobertura.vigente(o.id).orElse(null));
            return m;
        }).toList();
        return Map.of("cliente", documento, "obligaciones", filas);
    }

    @GetMapping("/calculos-cobertura/{id}")
    @PreAuthorize(Roles.LECTURA)
    @Operation(summary = "Cálculo de cobertura completo: entradas, reglas, resultado y traza")
    public CalculoCobertura calculo(@PathVariable UUID id) {
        return cobertura.calculo(id);
    }

    @PostMapping("/calculos-cobertura/{id}/reproduccion")
    @PreAuthorize(Roles.LECTURA)
    @Operation(summary = "Reproducir un cálculo pasado y verificar su hash (RF-0804)")
    public Map<String, Object> reproducir(@PathVariable UUID id) {
        return cobertura.reproducir(id);
    }

    @PostMapping("/coberturas/recalculo")
    @PreAuthorize(Roles.CALCULA_COBERTURA)
    @Operation(summary = "Recalcular la cobertura de todo el portafolio (normalmente nocturno)")
    public CoberturaService.ResumenRecalculo recalcular() {
        return cobertura.recalcularPortafolio("RECALCULO_MANUAL");
    }

    private Obligacion obligacion(String numero) {
        return obligaciones.findByNumero(numero).orElseThrow(() -> Errores.noEncontrado("La obligación " + numero));
    }
}
