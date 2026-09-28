package co.bancopopular.garantias360.constitucion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActividadRepositorio extends JpaRepository<ActividadConstitucion, UUID> {

    List<ActividadConstitucion> findByGarantiaIdOrderByOrdenAscCodigoAsc(UUID garantiaId);

    Optional<ActividadConstitucion> findByGarantiaIdAndCodigo(UUID garantiaId, String codigo);

    boolean existsByGarantiaId(UUID garantiaId);
}
