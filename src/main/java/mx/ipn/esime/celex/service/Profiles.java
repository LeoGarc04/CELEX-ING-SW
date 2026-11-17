package mx.ipn.esime.celex.service;

import java.util.*;
import mx.ipn.esime.celex.repository.Db;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public final class Profiles {
  private Profiles() {}

  public static List<SimpleGrantedAuthority> authorities(Db db, int user) {
    var rows = db.rows("SELECT tipo_permiso FROM roles_permisos WHERE id_usuario=?", user);
    String profile =
        rows.stream().anyMatch(r -> "ADMIN".equals(r.get("tipo_permiso")))
            ? "ADMIN"
            : rows.stream().anyMatch(r -> "PROFESOR".equals(r.get("tipo_permiso")))
                ? "PROFESOR"
                : "USUARIO";
    return List.of(new SimpleGrantedAuthority(profile));
  }
}
