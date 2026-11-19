package mx.ipn.esime.celex.service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import mx.ipn.esime.celex.repository.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {
  private final Db db;
  private final Clock clock;

  public AdminService(Db db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  @Transactional
  public void call(int actor, String name, LocalDateTime start, LocalDateTime end, int capacity) {
    text(name, 100);
    if (!start.isBefore(end) || capacity < 1 || capacity > 10000)
      throw new IllegalArgumentException("Revisa fechas y capacidad (1–10000)");
    db.update(
        "INSERT INTO convocatorias(nombre,fecha_inicio_fila,fecha_fin_fila,capacidad_n_fila)"
            + " VALUES(?,?,?,?)",
        name,
        start,
        end,
        capacity);
    db.audit(actor, "Alta de convocatoria", name);
  }

  @Transactional
  public void configure(
      int actor, int id, int capacity, boolean closed, LocalDateTime start, LocalDateTime end) {
    var original = db.one("SELECT * FROM convocatorias WHERE id_convocatoria=? FOR UPDATE", id);
    if (start == null) start = QueueService.time(original.get("fecha_inicio_fila"));
    if (end == null) end = QueueService.time(original.get("fecha_fin_fila"));
    if (!start.isBefore(end))
      throw new IllegalArgumentException("El cierre debe ser posterior a la apertura");
    if (capacity < 1 || capacity > 10000) throw new IllegalArgumentException("Capacidad inválida");
    db.update(
        "UPDATE convocatorias SET capacidad_n_fila=?,estado=?,fecha_inicio_fila=?,fecha_fin_fila=?"
            + " WHERE id_convocatoria=?",
        capacity,
        closed
            ? "Cerrada"
            : ("Borrador".equals(original.get("estado")) ? "Borrador" : "Programada"),
        start,
        end,
        id);
    db.audit(
        actor,
        "Configuración de convocatoria",
        id + " · capacidad " + capacity + " · cerrada " + closed);
  }

  @Transactional
  public void language(int actor, String name) {
    text(name, 50);
    db.update("INSERT INTO idiomas(nombre,activo) VALUES(?,TRUE)", name);
    db.audit(actor, "Alta de idioma", name);
  }

  @Transactional
  public void group(
      int actor,
      int call,
      int language,
      String level,
      String modality,
      String teacher,
      String room,
      String days,
      String time,
      int capacity) {
    db.one("SELECT * FROM convocatorias WHERE id_convocatoria=? FOR UPDATE", call);
    var selectedLanguage =
        db.one("SELECT * FROM idiomas WHERE id_idioma=? AND activo=TRUE", language);
    EnglishLevels.requireValid((String) selectedLanguage.get("nombre"), level);
    text(level, 20);
    text(modality, 30);
    text(teacher, 150);
    text(room, 50);
    text(days, 50);
    Rules.interval(time);
    if (!days.matches("[1-6](,[1-6])*"))
      throw new IllegalArgumentException(
          "Días: usa números ISO 1=lunes a 6=sábado, separados por coma");
    if (capacity < 1 || capacity > 10000) throw new IllegalArgumentException("Cupo inválido");
    for (var g :
        db.rows("SELECT * FROM grupos WHERE id_convocatoria=? AND estado='Activo'", call)) {
      if ((teacher.equalsIgnoreCase((String) g.get("profesor"))
              || room.equalsIgnoreCase((String) g.get("aula")))
          && Rules.overlaps(days, time, (String) g.get("dias"), (String) g.get("horario")))
        throw new IllegalArgumentException(
            "El profesor o el aula ya tiene un grupo en ese horario.");
    }
    db.update(
        "INSERT INTO"
            + " grupos(id_convocatoria,id_idioma,nivel,modalidad,profesor,aula,dias,horario,cupo_total,cupo_disponible)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
        call,
        language,
        level,
        modality,
        teacher,
        room,
        days,
        time,
        capacity,
        capacity);
    db.audit(actor, "Alta de grupo", call + " · " + level + " · " + time);
  }

  @Transactional
  public void editGroup(
      int actor,
      int id,
      String teacher,
      String room,
      String days,
      String time,
      int capacity,
      boolean active) {
    var original = db.one("SELECT * FROM grupos WHERE id_grupo=?", id);
    int call = Db.number(original, "id_convocatoria");
    db.one("SELECT * FROM convocatorias WHERE id_convocatoria=? FOR UPDATE", call);
    var current = db.one("SELECT * FROM grupos WHERE id_grupo=? FOR UPDATE", id);
    text(teacher, 150);
    text(room, 50);
    text(days, 50);
    Rules.interval(time);
    if (!days.matches("[1-6](,[1-6])*"))
      throw new IllegalArgumentException("Selecciona días de lunes a sábado");
    int occupied = Db.number(current, "cupo_total") - Db.number(current, "cupo_disponible");
    if (capacity < occupied || capacity < 1 || capacity > 10000)
      throw new IllegalArgumentException("El cupo total no puede ser menor a los lugares ocupados");
    if (active)
      for (var g :
          db.rows(
              "SELECT * FROM grupos WHERE id_convocatoria=? AND id_grupo<>? AND estado='Activo'",
              call,
              id))
        if ((teacher.equalsIgnoreCase((String) g.get("profesor"))
                || room.equalsIgnoreCase((String) g.get("aula")))
            && Rules.overlaps(days, time, (String) g.get("dias"), (String) g.get("horario")))
          throw new IllegalArgumentException("Traslape de profesor o aula");
    db.update(
        "UPDATE grupos SET"
            + " profesor=?,aula=?,dias=?,horario=?,cupo_total=?,cupo_disponible=?,estado=? WHERE"
            + " id_grupo=?",
        teacher,
        room,
        days,
        time,
        capacity,
        capacity - occupied,
        active ? "Activo" : "Inactivo",
        id);
    if (!teacher.equals(current.get("profesor"))) {
      db.update("DELETE FROM roles_permisos WHERE tipo_permiso=?", "DOCENTE_GRUPO_" + id);
      db.audit(
          actor,
          "Retiro de asignación docente",
          "Grupo " + id + " · cambio de profesor registrado");
    }
    db.audit(actor, "Cambio de grupo", "Grupo " + id + " · " + time + " · cupo " + capacity);
  }

  @Transactional
  public void deleteGroup(int actor, int id) {
    var original = db.one("SELECT * FROM grupos WHERE id_grupo=?", id);
    db.one(
        "SELECT * FROM convocatorias WHERE id_convocatoria=? FOR UPDATE",
        Db.number(original, "id_convocatoria"));
    var group = db.one("SELECT * FROM grupos WHERE id_grupo=? FOR UPDATE", id);
    if (!db.rows("SELECT id_solicitud FROM solicitudes_inscripcion WHERE id_grupo=?", id).isEmpty()
        || Db.number(group, "cupo_total") != Db.number(group, "cupo_disponible"))
      throw new IllegalArgumentException(
          "Este grupo tiene solicitudes o lugares ocupados. Desactívalo para conservar el"
              + " historial.");
    db.update("DELETE FROM roles_permisos WHERE tipo_permiso=?", "DOCENTE_GRUPO_" + id);
    db.update("DELETE FROM grupos WHERE id_grupo=?", id);
    db.audit(
        actor,
        "Eliminación de grupo",
        "Grupo " + id + " · " + group.get("nivel") + " · " + group.get("horario"));
  }

  @Transactional
  public void languageState(int actor, int id, boolean active) {
    db.update("UPDATE idiomas SET activo=? WHERE id_idioma=?", active, id);
    db.audit(actor, "Cambio de idioma", id + " · activo " + active);
  }

  @Transactional
  public void priority(
      int actor, int user, boolean scholar, int score, BigDecimal average, boolean newcomer) {
    if (average.signum() < 0 || average.compareTo(BigDecimal.TEN) > 0)
      throw new IllegalArgumentException("Promedio entre 0 y 10");
    Rules.priority(scholar, score, true, average, newcomer);
    for (var call : db.rows("SELECT * FROM convocatorias ORDER BY id_convocatoria FOR UPDATE")) {
      var now = LocalDateTime.now(clock);
      if (!now.isBefore(QueueService.time(call.get("fecha_inicio_fila")))
          && now.isBefore(QueueService.time(call.get("fecha_fin_fila")))
          && !"Cerrada".equals(call.get("estado")))
        throw new IllegalArgumentException("Registra prioridades antes de abrir la fila.");
    }
    db.one("SELECT * FROM usuarios WHERE id_usuario=? FOR UPDATE", user);
    db.update(
        "INSERT INTO"
            + " prioridades_usuario(id_usuario,es_becario,puntaje_beca,promedio_anterior,es_alumno_nuevo,registrado_por)"
            + " VALUES(?,?,?,?,?,?)",
        user,
        scholar,
        score,
        average,
        newcomer,
        actor);
    db.audit(actor, "Registro de prioridad", "Usuario " + user);
  }

  @Transactional
  public String history(
      int actor,
      int user,
      int language,
      String level,
      Integer grade,
      int inactive,
      boolean placement) {
    db.one("SELECT * FROM usuarios WHERE id_usuario=? FOR UPDATE", user);
    var selectedLanguage = db.one("SELECT * FROM idiomas WHERE id_idioma=?", language);
    EnglishLevels.requireValid((String) selectedLanguage.get("nombre"), level);
    text(level, 20);
    if ((grade != null && (grade < 0 || grade > 100)) || inactive < 0)
      throw new IllegalArgumentException(
          "Calificación 0–100 y periodos de inactividad no negativos");
    db.update(
        "INSERT INTO"
            + " historial_academico(id_usuario,id_idioma,nivel_actual,calificacion,acreditado,requiere_examen_colocacion,periodos_inactivo,registrado_por,fecha_registro)"
            + " VALUES(?,?,?,?,?,?,?,?,?)",
        user,
        language,
        level,
        grade,
        grade != null && grade >= 80,
        placement,
        inactive,
        actor,
        LocalDateTime.now(clock));
    db.audit(actor, "Registro académico", "Usuario " + user + " · " + level);
    var result = new HashMap<String, Object>();
    result.put("nivel_actual", level);
    result.put("calificacion", grade);
    result.put("periodos_inactivo", inactive);
    result.put("requiere_examen_colocacion", placement);
    return Rules.academicDecision(result);
  }

  @Transactional
  public void profile(int actor, int user, String profile) {
    if (!Set.of("ADMIN", "PROFESOR", "USUARIO").contains(profile))
      throw new IllegalArgumentException("Perfil inválido");
    db.rows(
        "SELECT u.id_usuario FROM usuarios u WHERE EXISTS(SELECT 1 FROM roles_permisos r WHERE"
            + " r.id_usuario=u.id_usuario AND r.tipo_permiso='ADMIN') ORDER BY u.id_usuario FOR"
            + " UPDATE");
    if (db.rows(
            "SELECT id_usuario FROM roles_permisos WHERE id_usuario=? AND tipo_permiso='ADMIN' FOR"
                + " UPDATE",
            actor)
        .isEmpty())
      throw new org.springframework.security.access.AccessDeniedException(
          "Solo el administrador cambia perfiles");
    var account = db.one("SELECT * FROM usuarios WHERE id_usuario=? FOR UPDATE", user);
    if (actor == user && !profile.equals("ADMIN"))
      throw new IllegalArgumentException("No puedes retirar tu propio perfil de administrador");
    if (!profile.equals("USUARIO") && !"Activa".equals(account.get("estado_cuenta")))
      throw new IllegalArgumentException(
          "La cuenta debe verificarse antes de asignarle ese perfil");
    if (profile.equals("PROFESOR"))
      db.update(
          "DELETE FROM roles_permisos WHERE id_usuario=? AND"
              + " LEFT(tipo_permiso,14)<>'DOCENTE_GRUPO_'",
          user);
    else db.update("DELETE FROM roles_permisos WHERE id_usuario=?", user);
    if (!profile.equals("USUARIO"))
      db.update("INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,?)", user, profile);
    db.audit(actor, "Cambio de perfil", "Usuario " + user + " · " + profile);
  }

  @Transactional
  public void permissions(int actor, int user, Set<String> permissions) {
    db.rows(
        "SELECT id_usuario FROM usuarios WHERE id_usuario IN (?,?) ORDER BY id_usuario FOR UPDATE",
        actor,
        user);
    if (Profiles.authorities(db, actor).stream()
        .noneMatch(a -> a.getAuthority().equals("GESTION_PERMISOS")))
      throw new org.springframework.security.access.AccessDeniedException(
          "Sin permiso para gestionar permisos");
    if (actor == user)
      throw new IllegalArgumentException("No puedes limitar tus propios permisos administrativos.");
    if (!Profiles.ADMIN_PERMISSIONS.containsAll(permissions))
      throw new IllegalArgumentException("Permiso inválido");
    if (db.rows(
            "SELECT id_usuario FROM roles_permisos WHERE id_usuario=? AND tipo_permiso='ADMIN'",
            user)
        .isEmpty())
      throw new IllegalArgumentException("La cuenta debe tener perfil Administrador.");
    db.update(
        "DELETE FROM roles_permisos WHERE id_usuario=? AND tipo_permiso IN"
            + " ('CONFIGURACION','ACADEMICO','FINANCIERO','AUDITORIA','PERMISOS_LIMITADOS')",
        user);
    db.update(
        "INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,'PERMISOS_LIMITADOS')", user);
    for (String permission : permissions)
      db.update(
          "INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,?)", user, permission);
    db.audit(
        actor,
        "Cambio de permisos administrativos",
        "Usuario " + user + " · " + new TreeSet<>(permissions));
  }

  private static void text(String value, int max) {
    if (value == null || value.isBlank() || value.length() > max)
      throw new IllegalArgumentException("Campo obligatorio; longitud máxima " + max);
  }
}
