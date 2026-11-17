package mx.ipn.esime.celex.repository;

import java.util.Optional;
import mx.ipn.esime.celex.entity.usuario;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsuarioRepository extends JpaRepository<usuario, Integer> {

  Optional<usuario> findByCorreo(String correo);

  boolean existsByCorreo(String correo);

  boolean existsByCurp(String curp);
}
