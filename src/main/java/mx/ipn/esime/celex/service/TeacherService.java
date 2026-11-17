package mx.ipn.esime.celex.service;

import java.time.*;
import java.util.*;
import mx.ipn.esime.celex.repository.Db;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeacherService {
  private final Db db;
  private final Clock clock;

  public TeacherService(Db db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  private static String grant(int group) {
    return "DOCENTE_GRUPO_" + group;
  }

  private static String action(int group, int student) {
    return "Calificación G" + group + " U" + student;
  }

  private void requireTeacher(int teacher, int group) {
    if (db.rows(
            "SELECT u.id_usuario FROM usuarios u WHERE u.id_usuario=? AND u.estado_cuenta='Activa'"
                + " AND EXISTS(SELECT 1 FROM roles_permisos p WHERE p.id_usuario=u.id_usuario AND"
                + " p.tipo_permiso='PROFESOR') AND NOT EXISTS(SELECT 1 FROM roles_permisos p WHERE"
                + " p.id_usuario=u.id_usuario AND p.tipo_permiso='ADMIN') AND EXISTS(SELECT 1 FROM"
                + " roles_permisos r WHERE r.id_usuario=u.id_usuario AND r.tipo_permiso=?)",
            teacher,
            grant(group))
        .isEmpty())
      throw new AccessDeniedException("Solo puedes consultar y calificar tus grupos asignados");
  }

  public List<Map<String, Object>> groups(int teacher) {
    return db.rows(
        "SELECT g.*,i.nombre AS idioma,c.nombre AS convocatoria FROM grupos g JOIN idiomas i ON"
            + " i.id_idioma=g.id_idioma JOIN convocatorias c ON c.id_convocatoria=g.id_convocatoria"
            + " WHERE EXISTS(SELECT 1 FROM roles_permisos r WHERE r.id_usuario=? AND"
            + " r.tipo_permiso=CONCAT('DOCENTE_GRUPO_',g.id_grupo)) ORDER BY g.id_grupo DESC",
        teacher);
  }

  public Map<String, Object> group(int teacher, int group) {
    requireTeacher(teacher, group);
    return db.one(
        "SELECT g.*,i.nombre AS idioma FROM grupos g JOIN idiomas i ON i.id_idioma=g.id_idioma"
            + " WHERE g.id_grupo=?",
        group);
  }

  public List<Map<String, Object>> students(int teacher, int group) {
    requireTeacher(teacher, group);
    return db
        .rows(
            "SELECT DISTINCT u.id_usuario,u.nombre_completo FROM solicitudes_inscripcion s JOIN"
                + " usuarios u ON u.id_usuario=s.id_usuario WHERE s.id_grupo=? AND"
                + " s.estado_inscripcion='Confirmada' ORDER BY u.nombre_completo",
            group)
        .stream()
        .map(
            row -> {
              Map<String, Object> copy = new HashMap<>(row);
              var records =
                  db.rows(
                      "SELECT h.calificacion,h.fecha_registro FROM historial_academico h JOIN"
                          + " bitacora_acciones b ON b.detalles=CONCAT('Historial ',h.id_historial)"
                          + " WHERE b.accion=? ORDER BY b.id_bitacora DESC LIMIT 1",
                      action(group, Db.number(row, "id_usuario")));
              copy.put(
                  "calificacion", records.isEmpty() ? null : records.get(0).get("calificacion"));
              copy.put("fecha", records.isEmpty() ? null : records.get(0).get("fecha_registro"));
              return copy;
            })
        .toList();
  }

  @Transactional
  public void assign(int actor, int group, Integer teacher) {
    if (db.rows(
            "SELECT id_usuario FROM roles_permisos WHERE id_usuario=? AND tipo_permiso='ADMIN'",
            actor)
        .isEmpty()) throw new AccessDeniedException("Solo el administrador asigna profesores");
    var initial = db.one("SELECT id_convocatoria FROM grupos WHERE id_grupo=?", group);
    db.one(
        "SELECT id_convocatoria FROM convocatorias WHERE id_convocatoria=? FOR UPDATE",
        initial.get("id_convocatoria"));
    var selected = db.one("SELECT * FROM grupos WHERE id_grupo=? FOR UPDATE", group);
    Map<String, Object> account = null;
    if (teacher != null) {
      account = db.one("SELECT * FROM usuarios WHERE id_usuario=? FOR UPDATE", teacher);
      if (!"Activa".equals(account.get("estado_cuenta"))
          || !Profiles.authorities(db, teacher).get(0).getAuthority().equals("PROFESOR"))
        throw new IllegalArgumentException("Selecciona una cuenta activa con perfil Profesor");
    }
    if (teacher != null && "Activo".equals(selected.get("estado"))) {
      for (var other :
          db.rows(
              "SELECT * FROM grupos WHERE id_convocatoria=? AND id_grupo<>? AND estado='Activo'",
              selected.get("id_convocatoria"),
              group)) {
        if (account
                .get("nombre_completo")
                .toString()
                .equalsIgnoreCase(other.get("profesor").toString())
            && Rules.overlaps(
                (String) selected.get("dias"),
                (String) selected.get("horario"),
                (String) other.get("dias"),
                (String) other.get("horario")))
          throw new IllegalArgumentException("El profesor ya tiene un grupo en ese horario");
      }
    }
    db.update("DELETE FROM roles_permisos WHERE tipo_permiso=?", grant(group));
    if (teacher != null) {
      db.update(
          "INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,?)", teacher, grant(group));
      db.update(
          "UPDATE grupos SET profesor=? WHERE id_grupo=?", account.get("nombre_completo"), group);
    }
    db.audit(
        actor,
        "Asignación de profesor",
        "Grupo "
            + group
            + " · "
            + (teacher == null ? "Sin cuenta asignada" : "Usuario " + teacher));
  }

  @Transactional
  public String grade(int teacher, int group, int student, int grade) {
    var g = db.one("SELECT * FROM grupos WHERE id_grupo=? FOR UPDATE", group);
    db.one("SELECT id_usuario FROM usuarios WHERE id_usuario=? FOR UPDATE", teacher);
    requireTeacher(teacher, group);
    if (grade < 0 || grade > 100)
      throw new IllegalArgumentException("La calificación debe estar entre 0 y 100");
    if (db.rows(
            "SELECT id_solicitud FROM solicitudes_inscripcion WHERE id_grupo=? AND id_usuario=? AND"
                + " estado_inscripcion='Confirmada' FOR UPDATE",
            group,
            student)
        .isEmpty())
      throw new AccessDeniedException(
          "El alumno no tiene una inscripción confirmada en este grupo");
    int history =
        db.id(
            "INSERT INTO"
                + " historial_academico(id_usuario,id_idioma,nivel_actual,calificacion,acreditado,requiere_examen_colocacion,periodos_inactivo,registrado_por,fecha_registro)"
                + " VALUES(?,?,?,?,?,FALSE,0,?,?)",
            student,
            g.get("id_idioma"),
            g.get("nivel"),
            grade,
            grade >= 80,
            teacher,
            LocalDateTime.now(clock));
    db.audit(teacher, action(group, student), "Historial " + history);
    return grade >= 80
        ? "Calificación guardada: nivel acreditado."
        : "Calificación guardada: nivel no acreditado; debe repetir el nivel conforme a las reglas"
            + " académicas.";
  }
}
