package co.bancopopular.garantias360.reglas;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class ReglaRepositorios {

    private ReglaRepositorios() {
    }

    public interface Reglas extends JpaRepository<Regla, UUID> {
        Optional<Regla> findByCodigo(String codigo);

        List<Regla> findAllByOrderByTipoAscCodigoAsc();
    }

    public interface Versiones extends JpaRepository<ReglaVersion, UUID> {
        List<ReglaVersion> findByReglaIdOrderByNumeroDesc(UUID reglaId);

        Optional<ReglaVersion> findByReglaIdAndNumero(UUID reglaId, int numero);

        List<ReglaVersion> findByEstado(ReglaVersion.EstadoRegla estado);
    }
}
