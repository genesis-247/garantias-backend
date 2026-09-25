package co.bancopopular.garantias360.obligacion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ObligacionRepositorio extends JpaRepository<Obligacion, UUID> {

    Optional<Obligacion> findByNumero(String numero);

    List<Obligacion> findByClienteDocumento(String documento);

    List<Obligacion> findByIdIn(Collection<UUID> ids);
}
