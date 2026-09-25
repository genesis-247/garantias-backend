package co.bancopopular.garantias360.cobertura;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class CoberturaRepositorios {

    private CoberturaRepositorios() {
    }

    public interface Calculos extends JpaRepository<CalculoCobertura, UUID> {
        @Query(value = "select * from calculo_cobertura where ?1 = any(obligaciones) order by fecha_corte desc limit ?2",
                nativeQuery = true)
        List<CalculoCobertura> deObligacion(UUID obligacionId, int limite);

        @Query(value = "select * from calculo_cobertura where ?1 = any(garantias) order by fecha_corte desc limit ?2",
                nativeQuery = true)
        List<CalculoCobertura> deGarantia(UUID garantiaId, int limite);
    }

    public interface Vigentes extends JpaRepository<CoberturaVigente, UUID> {
        List<CoberturaVigente> findByObligacionIdIn(Collection<UUID> ids);
    }
}
