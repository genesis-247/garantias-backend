package co.bancopopular.garantias360.garantia;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class Repositorios {

    private Repositorios() {
    }

    public interface Garantias extends JpaRepository<Garantia, UUID>, JpaSpecificationExecutor<Garantia> {
        Optional<Garantia> findByCodigo(String codigo);

        Optional<Garantia> findByAplicativoOrigenAndReferenciaExterna(String aplicativo, String referencia);

        List<Garantia> findByIdIn(Collection<UUID> ids);

        @Query(value = "select * from garantia where tipo_codigo = ?1 and atributos ->> ?2 = ?3 and macroestado not in ('ANULADA','CIERRE') limit 1",
                nativeQuery = true)
        Optional<Garantia> porLlaveNatural(String tipo, String campo, String valor);
    }

    public interface Participantes extends JpaRepository<Participante, UUID> {
        List<Participante> findByGarantiaId(UUID garantiaId);
    }

    public interface Vinculos extends JpaRepository<Vinculo, UUID> {
        List<Vinculo> findByGarantiaIdAndVigenteTrue(UUID garantiaId);

        List<Vinculo> findByObligacionIdAndVigenteTrue(UUID obligacionId);

        List<Vinculo> findByGarantiaIdInAndVigenteTrue(Collection<UUID> garantias);

        List<Vinculo> findByObligacionIdInAndVigenteTrue(Collection<UUID> obligaciones);

        List<Vinculo> findByVigenteTrue();

        Optional<Vinculo> findByGarantiaIdAndObligacionId(UUID garantiaId, UUID obligacionId);
    }

    public interface Valoraciones extends JpaRepository<Valoracion, UUID> {
        List<Valoracion> findByGarantiaIdOrderByFechaDescCreatedAtDesc(UUID garantiaId);

        Optional<Valoracion> findFirstByGarantiaIdOrderByFechaDescCreatedAtDesc(UUID garantiaId);

        Page<Valoracion> findAllByOrderByCreatedAtDesc(Pageable pageable);
    }
}
