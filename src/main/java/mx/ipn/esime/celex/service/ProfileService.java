package mx.ipn.esime.celex.service;

import java.time.*;
import java.util.*;
import mx.ipn.esime.celex.repository.Db;
import org.springframework.security.authentication.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {
  @org.springframework.beans.factory.annotation.Autowired private AgePolicy ages;
  private final Db db;
  private final PasswordEncoder encoder;
  private final Clock clock;
  @org.springframework.beans.factory.annotation.Autowired private ScholarshipService scholarships;

  public ProfileService(Db db, PasswordEncoder encoder, Clock clock) {
    this.db = db;
    this.encoder = encoder;
    this.clock = clock;
  }

  public Map<String, Object> get(int user) {
    var account =
        new HashMap<String, Object>(
            db.one(
                "SELECT"
                    + " id_usuario,nombre_completo,telefono,correo,curp,boleta,tipo_usuario,estado_cuenta"
                    + " FROM usuarios WHERE id_usuario=? AND estado_cuenta='Activa'",
                user));
    account.put(
        "inscripciones",
        db.rows(
            "SELECT"
                + " s.id_solicitud,s.folio,s.estado_inscripcion,g.nivel,g.modalidad,g.horario,i.nombre"
                + " AS idioma,EXISTS(SELECT 1 FROM comprobantes_oficiales c WHERE"
                + " c.id_solicitud=s.id_solicitud) AS comprobante FROM solicitudes_inscripcion s"
                + " JOIN grupos g ON g.id_grupo=s.id_grupo JOIN idiomas i ON"
                + " i.id_idioma=g.id_idioma WHERE s.id_usuario=? ORDER BY s.id_solicitud DESC",
            user));
    account.put("becas", scholarships.forUser(user));
    account.put("convocatoriasBeca", scholarships.options(user));
    account.put("fechaNacimiento", ages.birth(user));
    account.put("edadValida", ages.eligible(user));
    var priority =
        db.rows(
            "SELECT es_becario FROM prioridades_usuario WHERE id_usuario=? ORDER BY id_prioridad"
                + " DESC LIMIT 1",
            user);
    account.put(
        "prioridadBecario", !priority.isEmpty() && Rules.yes(priority.get(0).get("es_becario")));
    return account;
  }

  @Transactional
  public void update(int user, String name, String phone) {
    name = name == null ? "" : name.trim().replaceAll("\\s+", " ");
    phone = phone == null ? "" : phone.trim();
    if (name.isBlank() || name.length() > 150 || !phone.matches("[+0-9 ()-]{7,15}"))
      throw new IllegalArgumentException("Revisa el nombre y el teléfono");
    db.rows("SELECT id_convocatoria FROM convocatorias ORDER BY id_convocatoria FOR UPDATE");
    var groups = db.rows("SELECT * FROM grupos ORDER BY id_grupo FOR UPDATE");
    var account =
        db.one(
            "SELECT * FROM usuarios WHERE id_usuario=? AND estado_cuenta='Activa' FOR UPDATE",
            user);
    if (!name.equals(account.get("nombre_completo"))) {
      var assigned =
          db.rows(
              "SELECT g.* FROM grupos g WHERE EXISTS(SELECT 1 FROM roles_permisos r WHERE"
                  + " r.id_usuario=? AND r.tipo_permiso=CONCAT('DOCENTE_GRUPO_',g.id_grupo))",
              user);
      for (var group : assigned)
        for (var other : groups)
          if (!Objects.equals(group.get("id_grupo"), other.get("id_grupo"))
              && Objects.equals(group.get("id_convocatoria"), other.get("id_convocatoria"))
              && "Activo".equals(group.get("estado"))
              && "Activo".equals(other.get("estado"))
              && name.equalsIgnoreCase(other.get("profesor").toString())
              && Rules.overlaps(
                  (String) group.get("dias"),
                  (String) group.get("horario"),
                  (String) other.get("dias"),
                  (String) other.get("horario")))
            throw new IllegalArgumentException(
                "El nombre coincide con un profesor que tiene otro grupo en ese horario. Consulta"
                    + " al administrador.");
      db.update(
          "UPDATE grupos SET profesor=? WHERE EXISTS(SELECT 1 FROM roles_permisos r WHERE"
              + " r.id_usuario=? AND r.tipo_permiso=CONCAT('DOCENTE_GRUPO_',grupos.id_grupo))",
          name,
          user);
    }
    db.update(
        "UPDATE usuarios SET nombre_completo=?,telefono=? WHERE id_usuario=?", name, phone, user);
    db.audit(user, "Actualización de perfil", "Nombre y teléfono");
  }

  @Transactional(noRollbackFor = AuthenticationException.class)
  public String password(int user, String current, String next, String confirmation) {
    AccountService.validatePassword(next);
    if (!Objects.equals(next, confirmation))
      throw new IllegalArgumentException("Las contraseñas nuevas deben coincidir");
    var account =
        db.one(
            "SELECT * FROM usuarios WHERE id_usuario=? AND estado_cuenta='Activa' FOR UPDATE",
            user);
    var now = LocalDateTime.now(clock);
    Object until = account.get("bloqueado_hasta");
    if (until != null && QueueService.time(until).isAfter(now))
      throw new LockedException(
          "Espera a que termine el bloqueo de diez minutos antes de cambiar la contraseña.");
    if (current == null || !encoder.matches(current, (String) account.get("password_hash"))) {
      int attempts = until != null ? 1 : Db.number(account, "intentos_fallidos") + 1;
      db.update(
          "UPDATE usuarios SET intentos_fallidos=?,bloqueado_hasta=? WHERE id_usuario=?",
          attempts,
          attempts >= 5 ? now.plusMinutes(10) : null,
          user);
      throw new BadCredentialsException(
          "Contraseña actual incorrecta. Después de cinco intentos fallidos se bloquea el acceso"
              + " durante diez minutos.");
    }
    String hash = encoder.encode(next);
    db.update(
        "UPDATE usuarios SET password_hash=?,intentos_fallidos=0,bloqueado_hasta=NULL WHERE"
            + " id_usuario=?",
        hash,
        user);
    db.audit(user, "Cambio de contraseña", "Cambio desde Mi perfil");
    return hash;
  }
}
