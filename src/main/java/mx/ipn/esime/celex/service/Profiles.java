package mx.ipn.esime.celex.service;

import java.util.*;
import mx.ipn.esime.celex.repository.Db;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public final class Profiles {
  public static final Set<String> ADMIN_PERMISSIONS =
      Set.of("CONFIGURACION", "ACADEMICO", "FINANCIERO", "AUDITORIA");

  private Profiles() {}

  public static List<SimpleGrantedAuthority> authorities(Db db, int user) {
    var values =
        db.rows("SELECT tipo_permiso FROM roles_permisos WHERE id_usuario=?", user).stream()
            .map(r -> (String) r.get("tipo_permiso"))
            .collect(java.util.stream.Collectors.toSet());
    var result = new ArrayList<SimpleGrantedAuthority>();
    if (values.contains("ADMIN")) {
      result.add(new SimpleGrantedAuthority("ADMIN"));
      boolean restricted = values.contains("PERMISOS_LIMITADOS");
      for (String permission : ADMIN_PERMISSIONS)
        if (!restricted || values.contains(permission))
          result.add(new SimpleGrantedAuthority(permission));
      if (!restricted) result.add(new SimpleGrantedAuthority("GESTION_PERMISOS"));
    } else
      result.add(new SimpleGrantedAuthority(values.contains("PROFESOR") ? "PROFESOR" : "USUARIO"));
    return result;
  }
}
