package mx.ipn.esime.celex.service;

import java.time.*;
import java.util.*;
import mx.ipn.esime.celex.repository.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CallManagement {
  @org.springframework.beans.factory.annotation.Autowired private TeacherService teachers;
  private final Db db;
  private final AdminService admin;
  private final CallSettings settings;
  private final Clock clock;

  public CallManagement(Db db, AdminService admin, CallSettings settings, Clock clock) {
    this.db = db;
    this.admin = admin;
    this.settings = settings;
    this.clock = clock;
  }

  @Transactional
  public int create(
      int actor,
      String name,
      LocalDateTime start,
      LocalDateTime end,
      int capacity,
      LocalDateTime classes,
      int examCapacity,
      List<Integer> sources,
      Map<String, String> initial) {
    if (name == null
        || name.isBlank()
        || name.length() > 100
        || !start.isBefore(end)
        || capacity < 1
        || capacity > 10000)
      throw new IllegalArgumentException("Revisa nombre, fechas y capacidad.");
    var ids = sources == null ? List.<Integer>of() : sources.stream().distinct().sorted().toList();
    if (ids.isEmpty() && (initial == null || initial.getOrDefault("idioma", "").isBlank()))
      throw new IllegalArgumentException(
          "Asigna al menos un grupo existente o crea el primer grupo al registrar la"
              + " convocatoria.");
    int call =
        db.id(
            "INSERT INTO"
                + " convocatorias(nombre,fecha_inicio_fila,fecha_fin_fila,capacidad_n_fila,estado)"
                + " VALUES(?,?,?,?,'Borrador')",
            name,
            start,
            end,
            capacity);
    settings.configure(actor, call, classes, examCapacity);
    for (int source : ids) {
      var g =
          db.one(
              "SELECT g.* FROM grupos g JOIN idiomas i ON i.id_idioma=g.id_idioma WHERE"
                  + " g.id_grupo=? AND g.estado='Activo' AND i.activo=TRUE AND g.nivel NOT IN"
                  + " ('Colocación','Trámite')",
              source);
      admin.group(
          actor,
          call,
          Db.number(g, "id_idioma"),
          (String) g.get("nivel"),
          (String) g.get("modalidad"),
          (String) g.get("profesor"),
          (String) g.get("aula"),
          (String) g.get("dias"),
          (String) g.get("horario"),
          Db.number(g, "cupo_total"));
      int copied =
          Db.number(
              db.one(
                  "SELECT id_grupo FROM grupos WHERE id_convocatoria=? ORDER BY id_grupo DESC LIMIT"
                      + " 1",
                  call),
              "id_grupo");
      var assigned =
          db.rows(
              "SELECT DISTINCT r.id_usuario FROM roles_permisos r JOIN usuarios u ON"
                  + " u.id_usuario=r.id_usuario WHERE r.tipo_permiso=? AND u.estado_cuenta='Activa'"
                  + " AND EXISTS(SELECT 1 FROM roles_permisos p WHERE p.id_usuario=u.id_usuario AND"
                  + " p.tipo_permiso='PROFESOR')",
              "DOCENTE_GRUPO_" + source);
      if (!assigned.isEmpty())
        teachers.assign(actor, copied, Db.number(assigned.get(0), "id_usuario"));
    }
    if (initial != null
        && !initial.getOrDefault("idioma", "").isBlank()
        && Profiles.authorities(db, actor).stream()
            .noneMatch(p -> p.getAuthority().equals("ACADEMICO")))
      throw new org.springframework.security.access.AccessDeniedException(
          "Crear un grupo nuevo requiere permiso académico.");
    if (initial != null && !initial.getOrDefault("idioma", "").isBlank())
      admin.group(
          actor,
          call,
          Integer.parseInt(initial.get("idioma")),
          initial.get("nivel"),
          initial.get("modalidad"),
          initial.get("profesor"),
          initial.get("aula"),
          initial.get("dias"),
          initial.get("horario"),
          Integer.parseInt(initial.get("cupo")));
    db.audit(actor, "Alta de convocatoria con grupos", "Convocatoria " + call + " · borrador");
    return call;
  }

  @Transactional
  public void enable(int actor, int call) {
    var c = db.one("SELECT * FROM convocatorias WHERE id_convocatoria=? FOR UPDATE", call);
    if (!LocalDateTime.now(clock).isBefore(QueueService.time(c.get("fecha_fin_fila"))))
      throw new IllegalArgumentException(
          "Actualiza las fechas antes de habilitar una convocatoria vencida.");
    if (settings.classes(call) == null)
      throw new IllegalArgumentException(
          "Configura primero el inicio de clases y el cupo del examen.");
    if (db.rows(
            "SELECT id_grupo FROM grupos WHERE id_convocatoria=? AND estado='Activo' AND nivel NOT"
                + " IN ('Colocación','Trámite')",
            call)
        .isEmpty())
      throw new IllegalArgumentException("Asigna grupos antes de habilitar la convocatoria.");
    db.update("UPDATE convocatorias SET estado='Programada' WHERE id_convocatoria=?", call);
    db.audit(actor, "Habilitación de convocatoria", "Convocatoria " + call);
  }
}
