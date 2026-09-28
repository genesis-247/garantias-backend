package co.bancopopular.garantias360.configuracion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TipoGarantiaRepositorio extends JpaRepository<TipoGarantia, UUID> {

    Optional<TipoGarantia> findByCodigo(String codigo);

    List<TipoGarantia> findAllByOrderByNombre();

    interface Versiones extends JpaRepository<TipoGarantiaVersion, UUID> {

        @Query("select v from TipoGarantiaVersion v where v.tipoId = ?1 and v.estado = 'PUBLICADA'")
        Optional<TipoGarantiaVersion> publicada(UUID tipoId);

        @Query("select v from TipoGarantiaVersion v where v.tipoId = ?1 and v.estado in ('BORRADOR', 'EN_REVISION')")
        Optional<TipoGarantiaVersion> enCurso(UUID tipoId);

        Optional<TipoGarantiaVersion> findByTipoIdAndNumero(UUID tipoId, int numero);

        List<TipoGarantiaVersion> findByTipoIdOrderByNumeroDesc(UUID tipoId);

        @Query("select coalesce(max(v.numero), 0) from TipoGarantiaVersion v where v.tipoId = ?1")
        int ultimoNumero(UUID tipoId);
    }
}
