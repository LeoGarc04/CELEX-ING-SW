package mx.ipn.esime.celex.service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import mx.ipn.esime.celex.repository.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QueueService {
  private final Db db;
  private final Clock clock;

  @org.springframework.beans.factory.annotation.Value("${celex.queue.initial-estimate-seconds:180}")
  private long initialEstimate;

  public QueueService(Db db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  public LocalDateTime now() {
    return LocalDateTime.now(clock);
  }

  public Map<String, Object> lock(int call) {
    return db.one("SELECT * FROM convocatorias WHERE id_convocatoria=? FOR UPDATE", call);
  }

  public static LocalDateTime time(Object v) {
    return v instanceof java.sql.Timestamp t ? t.toLocalDateTime() : (LocalDateTime) v;
  }

  public String status(Map<String, Object> c) {
    if ("Cerrada".equals(c.get("estado")) || !now().isBefore(time(c.get("fecha_fin_fila"))))
      return "Cerrada";
    return now().isBefore(time(c.get("fecha_inicio_fila"))) ? "Programada" : "Abierta";
  }

  public void requireOpen(Map<String, Object> c) {
    if ("Cerrada".equals(c.get("estado"))
        || now().isBefore(time(c.get("fecha_inicio_fila")))
        || !now().isBefore(time(c.get("fecha_fin_fila"))))
      throw new IllegalArgumentException(
          "La fila está cerrada. Consulta las fechas de la convocatoria.");
  }

  public void expire(int call) {
    db.update(
        "UPDATE fila_virtual SET estado_turno='Expirado',posicion_actual=0 WHERE id_convocatoria=?"
            + " AND estado_turno IN ('En espera','Atendiendo') AND reserva_expira_en<=?",
        call,
        now());
  }

  public void order(int call) {
    var rows =
        db.rows(
            "SELECT f.id_turno FROM fila_virtual f LEFT JOIN prioridades_usuario p ON"
                + " p.id_usuario=f.id_usuario AND p.id_prioridad=(SELECT MAX(p2.id_prioridad) FROM"
                + " prioridades_usuario p2 WHERE p2.id_usuario=f.id_usuario) WHERE"
                + " f.id_convocatoria=? AND f.estado_turno IN ('En espera','Atendiendo') ORDER BY"
                + " CASE WHEN f.estado_turno='Atendiendo' THEN 0 ELSE 1"
                + " END,f.nivel_prioridad_calculado DESC,CASE WHEN f.nivel_prioridad_calculado=4"
                + " THEN COALESCE(p.puntaje_beca,0) ELSE 0 END DESC,f.fecha_ingreso,f.id_turno",
            call);
    int p = 0;
    for (var row : rows)
      db.update(
          "UPDATE fila_virtual SET posicion_actual=? WHERE id_turno=?", ++p, row.get("id_turno"));
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public String join(int user, int call) {
    var c = lock(call);
    requireOpen(c);
    expire(call);
    var u = db.one("SELECT * FROM usuarios WHERE id_usuario=? AND estado_cuenta='Activa'", user);
    var existing =
        db.rows(
            "SELECT * FROM fila_virtual WHERE id_usuario=? AND id_convocatoria=? ORDER BY id_turno",
            user,
            call);
    if (!existing.isEmpty()) {
      var t = existing.get(0);
      String state = (String) t.get("estado_turno");
      if ("Expirado".equals(state)) {
        requireSpace(c, call);
        db.update(
            "UPDATE fila_virtual SET estado_turno='En espera',reserva_expira_en=?,fecha_ingreso=?"
                + " WHERE id_turno=?",
            now().plusMinutes(3),
            now(),
            t.get("id_turno"));
        order(call);
      }
      return (String) t.get("folio");
    }
    requireSpace(c, call);
    var priorities =
        db.rows(
            "SELECT * FROM prioridades_usuario WHERE id_usuario=? ORDER BY id_prioridad DESC",
            user);
    int priority = 1;
    if (!priorities.isEmpty()) {
      var p = priorities.get(0);
      priority =
          Rules.priority(
              Rules.yes(p.get("es_becario")),
              Db.number(p, "puntaje_beca"),
              "Público general".equals(u.get("tipo_usuario")),
              (BigDecimal) p.get("promedio_anterior"),
              Rules.yes(p.get("es_alumno_nuevo")));
    }
    String folio = "CX" + UUID.randomUUID().toString().replace("-", "").substring(0, 28);
    db.update(
        "INSERT INTO"
            + " fila_virtual(folio,id_usuario,id_convocatoria,posicion_actual,nivel_prioridad_calculado,estado_turno,reserva_expira_en,fecha_ingreso)"
            + " VALUES(?,?,?,1,?,'En espera',?,?)",
        folio,
        user,
        call,
        priority,
        now().plusMinutes(3),
        now());
    order(call);
    db.audit(user, "Ingreso a fila", folio);
    return folio;
  }

  private void requireSpace(Map<String, Object> c, int call) {
    int count =
        Db.number(
            db.one(
                "SELECT COUNT(*) AS total FROM fila_virtual WHERE id_convocatoria=? AND"
                    + " estado_turno IN ('En espera','Atendiendo')",
                call),
            "total");
    if (count >= Db.number(c, "capacidad_n_fila"))
      throw new IllegalArgumentException("La fila alcanzó su capacidad. Intenta más tarde.");
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public Map<String, Object> heartbeat(int user, String folio) {
    var t = db.one("SELECT * FROM fila_virtual WHERE folio=? AND id_usuario=?", folio, user);
    int call = Db.number(t, "id_convocatoria");
    var c = lock(call);
    requireOpen(c);
    expire(call);
    order(call);
    t = db.one("SELECT * FROM fila_virtual WHERE folio=? AND id_usuario=?", folio, user);
    if (List.of("En espera", "Atendiendo").contains(t.get("estado_turno"))) {
      String state = Db.number(t, "posicion_actual") == 1 ? "Atendiendo" : "En espera";
      db.update(
          "UPDATE fila_virtual SET estado_turno=?,reserva_expira_en=? WHERE id_turno=?",
          state,
          now().plusMinutes(3),
          t.get("id_turno"));
    }
    var result =
        new HashMap<>(
            db.one("SELECT * FROM fila_virtual WHERE folio=? AND id_usuario=?", folio, user));
    var events =
        db.rows(
            "SELECT b.fecha_hora FROM bitacora_acciones b JOIN fila_virtual f ON b.detalles LIKE"
                + " CONCAT(f.folio,' · grupo %') WHERE b.accion='Selección de grupo' AND"
                + " f.id_convocatoria=? ORDER BY b.fecha_hora DESC LIMIT 10",
            call);
    Long estimate = null;
    if (events.size() > 1) {
      long elapsed =
          Duration.between(
                  time(events.get(events.size() - 1).get("fecha_hora")),
                  time(events.get(0).get("fecha_hora")))
              .getSeconds();
      estimate =
          Math.max(0, Db.number(result, "posicion_actual") - 1)
              * Math.max(1, elapsed / (events.size() - 1));
    }
    result.put("estimacion_inicial", estimate == null);
    if (estimate == null)
      estimate =
          Math.max(0, Db.number(result, "posicion_actual") - 1) * Math.max(1, initialEstimate);
    result.put("espera_estimada_segundos", estimate);
    return result;
  }
}
