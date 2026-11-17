package mx.ipn.esime.celex.service;

import mx.ipn.esime.celex.repository.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InitialAdminService {
  private final Db db;

  public InitialAdminService(Db db) {
    this.db = db;
  }

  @Transactional
  public void appoint(String email) {
    db.rows("SELECT id_usuario FROM usuarios ORDER BY id_usuario FOR UPDATE");
    if (!db.rows("SELECT id_permiso FROM roles_permisos WHERE tipo_permiso='ADMIN'").isEmpty())
      throw new IllegalArgumentException(
          "Ya hay un administrador. Los permisos deben gestionarse desde su panel.");
    var user =
        db.one(
            "SELECT id_usuario FROM usuarios WHERE correo=? AND estado_cuenta='Activa'",
            AccountService.normalize(email));
    int id = Db.number(user, "id_usuario");
    db.update("INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,'ADMIN')", id);
    db.audit(id, "Alta de administrador inicial", "Designado por operador local");
  }
}
