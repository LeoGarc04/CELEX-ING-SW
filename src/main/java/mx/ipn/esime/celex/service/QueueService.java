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
  @org.springframework.beans.factory.annotation.Autowired private CallSettings settings;
  @org.springframework.beans.factory.annotation.Autowired private AcademicRoutes routes;

  @org.springframework.beans.factory.annotation.Value("${celex.queue.batch-seconds:5}")
  private int batchSeconds = 5;

  public boolean grouped(int call) {
    return settings != null && settings.managed(call);
  }

  public boolean managed(int call) {
    return grouped(call);
  }

  private long cohort(Map<String, Object> c, Object entered) {
    long seconds = Duration.between(time(c.get("fecha_inicio_fila")), time(entered)).getSeconds();
    return Math.max(0, seconds) / Math.max(1, batchSeconds);
  }

  private LocalDateTime releaseAt(Map<String, Object> c, Map<String, Object> turn) {
    return time(c.get("fecha_inicio_fila"))
        .plusSeconds((cohort(c, turn.get("fecha_ingreso")) + 1) * Math.max(1, batchSeconds));
  }

  public boolean ready(Map<String, Object> turn) {
    if (!grouped(Db.number(turn, "id_convocatoria"))) return true;
    var c =
        db.one("SELECT * FROM convocatorias WHERE id_convocatoria=?", turn.get("id_convocatoria"));
    return !now().isBefore(releaseAt(c, turn));
  }

  private void requireAdmission(Map<String, Object> c) {
    if (!grouped(Db.number(c, "id_convocatoria"))) {
      requireOpen(c);
      return;
    }
    if (List.of("Borrador", "Cerrada").contains(c.get("estado"))
        || !now().isBefore(time(c.get("fecha_fin_fila"))))
      throw new IllegalArgumentException("La convocatoria no está habilitada o ya cerró.");
  }

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
    if (List.of("Borrador", "Cerrada").contains(c.get("estado"))
        || !now().isBefore(time(c.get("fecha_fin_fila")))) return "Cerrada";
    return now().isBefore(time(c.get("fecha_inicio_fila"))) ? "Programada" : "Abierta";
  }

  public void requireOpen(Map<String, Object> c) {
    if (List.of("Borrador", "Cerrada").contains(c.get("estado"))
        || now().isBefore(time(c.get("fecha_inicio_fila")))
        || !now().isBefore(time(c.get("fecha_fin_fila")))) {
      var next =
          db.rows(
              "SELECT fecha_inicio_fila FROM convocatorias WHERE estado<>'Cerrada' AND"
                  + " fecha_inicio_fila>? AND fecha_fin_fila>fecha_inicio_fila ORDER BY"
                  + " fecha_inicio_fila LIMIT 1",
              now());
      String date =
          next.isEmpty()
              ? "No hay una próxima apertura programada."
              : "Próxima apertura: "
                  + time(next.get(0).get("fecha_inicio_fila"))
                      .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                  + ".";
      throw new IllegalArgumentException("La fila está cerrada. " + date);
    }
  }

  public void expire(int call) {
    db.update(
        "UPDATE fila_virtual SET estado_turno='Expirado',posicion_actual=0 WHERE id_convocatoria=?"
            + " AND estado_turno IN ('En espera','Atendiendo') AND reserva_expira_en<=?",
        call,
        now());
  }

  public void order(int call) {
    boolean managed = grouped(call);
    var c = db.one("SELECT * FROM convocatorias WHERE id_convocatoria=?", call);
    Map<String, Integer> assigned = new HashMap<>();
    int last = 0;
    if (managed)
      for (var event :
          db.rows(
              "SELECT accion,detalles FROM bitacora_acciones WHERE accion LIKE ? ORDER BY"
                  + " id_bitacora",
              "Número turno · " + call + " · %")) {
        try {
          int number = Integer.parseInt((String) event.get("detalles"));
          assigned.put((String) event.get("accion"), number);
          last = Math.max(last, number);
        } catch (NumberFormatException ignored) {
        }
      }
    var rows =
        new ArrayList<>(
            db.rows(
                "SELECT f.*,u.tipo_usuario,p.id_prioridad AS"
                    + " prioridad_registrada,p.es_becario,p.es_alumno_nuevo,p.promedio_anterior,(EXISTS(SELECT"
                    + " 1 FROM historial_academico h WHERE h.id_usuario=f.id_usuario) OR"
                    + " EXISTS(SELECT 1 FROM solicitudes_inscripcion s JOIN grupos g ON"
                    + " g.id_grupo=s.id_grupo WHERE s.id_usuario=f.id_usuario AND"
                    + " s.estado_inscripcion='Confirmada' AND g.nivel NOT IN"
                    + " ('Colocación','Trámite'))) AS tiene_historial,COALESCE(p.puntaje_beca,0) AS"
                    + " puntaje FROM fila_virtual f JOIN usuarios u ON u.id_usuario=f.id_usuario"
                    + " LEFT JOIN prioridades_usuario p ON p.id_usuario=f.id_usuario AND"
                    + " p.id_prioridad=(SELECT MAX(p2.id_prioridad) FROM prioridades_usuario p2"
                    + " WHERE p2.id_usuario=f.id_usuario) WHERE f.id_convocatoria=? AND"
                    + " f.estado_turno IN ('En espera','Atendiendo')",
                call));
    if (managed)
      for (var row : rows) {
        String key = "Número turno · " + call + " · " + row.get("id_usuario");
        if (!assigned.containsKey(key) && !now().isBefore(releaseAt(c, row))) {
          boolean isNew =
              row.get("prioridad_registrada") == null
                  ? !Rules.yes(row.get("tiene_historial"))
                  : Rules.yes(row.get("es_alumno_nuevo"));
          int rank =
              Rules.priority(
                  Rules.yes(row.get("es_becario")),
                  Db.number(row, "puntaje"),
                  "Público general".equals(row.get("tipo_usuario")),
                  row.get("promedio_anterior") == null
                      ? BigDecimal.ZERO
                      : (BigDecimal) row.get("promedio_anterior"),
                  isNew);
          if ("Beneficiario"
              .equals(
                  settings
                      .read("Beca previa · " + call + " · " + row.get("id_usuario"))
                      .get("estado"))) rank = 4;
          if (rank != Db.number(row, "nivel_prioridad_calculado")) {
            db.update(
                "UPDATE fila_virtual SET nivel_prioridad_calculado=? WHERE id_turno=?",
                rank,
                row.get("id_turno"));
            row.put("nivel_prioridad_calculado", rank);
          }
        }
      }
    rows.sort(
        (a, b) -> {
          int cmp =
              Boolean.compare(
                  !"Atendiendo".equals(a.get("estado_turno")),
                  !"Atendiendo".equals(b.get("estado_turno")));
          if (cmp != 0) return cmp;
          if (managed) {
            cmp =
                Long.compare(cohort(c, a.get("fecha_ingreso")), cohort(c, b.get("fecha_ingreso")));
            if (cmp != 0) return cmp;
          }
          cmp =
              Integer.compare(
                  Db.number(b, "nivel_prioridad_calculado"),
                  Db.number(a, "nivel_prioridad_calculado"));
          if (cmp != 0) return cmp;
          if (Db.number(a, "nivel_prioridad_calculado") == 4) {
            cmp = Integer.compare(Db.number(b, "puntaje"), Db.number(a, "puntaje"));
            if (cmp != 0) return cmp;
          }
          cmp = time(a.get("fecha_ingreso")).compareTo(time(b.get("fecha_ingreso")));
          return cmp != 0
              ? cmp
              : Integer.compare(Db.number(a, "id_turno"), Db.number(b, "id_turno"));
        });
    int position = 0;
    for (var row : rows) {
      position++;
      if (Db.number(row, "posicion_actual") != position)
        db.update(
            "UPDATE fila_virtual SET posicion_actual=? WHERE id_turno=?",
            position,
            row.get("id_turno"));
      if (managed && !now().isBefore(releaseAt(c, row))) {
        String key = "Número turno · " + call + " · " + row.get("id_usuario");
        if (!assigned.containsKey(key)) {
          db.audit(Db.number(row, "id_usuario"), key, Integer.toString(++last));
          assigned.put(key, last);
        }
      }
    }
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public String join(int user, int call) {
    var c = lock(call);
    requireAdmission(c);
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
    int priority = grouped(call) && routes.newcomer(user) ? 2 : 1;
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
    if (grouped(call)
        && "Beneficiario"
            .equals(settings.read("Beca previa · " + call + " · " + user).get("estado")))
      priority = 4;
    String folio = null;
    for (int attempt = 0; attempt < 8; attempt++) {
      folio = newFolio();
      try {
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
        break;
      } catch (org.springframework.dao.DuplicateKeyException collision) {
        if (attempt == 7) throw collision;
      }
    }
    order(call);
    db.audit(user, "Ingreso a fila", folio);
    return folio;
  }

  private String newFolio() {
    String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    var random = new java.security.SecureRandom();
    StringBuilder candidate = new StringBuilder(8);
    for (int digit = 0; digit < 8; digit++)
      candidate.append(alphabet.charAt(random.nextInt(alphabet.length())));
    return candidate.toString();
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
    requireAdmission(c);
    expire(call);
    order(call);
    t = db.one("SELECT * FROM fila_virtual WHERE folio=? AND id_usuario=?", folio, user);
    if (List.of("En espera", "Atendiendo").contains(t.get("estado_turno"))) {
      String state = Db.number(t, "posicion_actual") == 1 && ready(t) ? "Atendiendo" : "En espera";
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
    int assigned =
        Db.number(
            db.one(
                "SELECT COUNT(*) AS total FROM fila_virtual WHERE id_convocatoria=? AND"
                    + " id_turno<=?",
                call,
                result.get("id_turno")),
            "total");
    if (grouped(call)) {
      var values =
          db.rows(
              "SELECT detalles FROM bitacora_acciones WHERE accion=? ORDER BY id_bitacora LIMIT 1",
              "Número turno · " + call + " · " + user);
      assigned = values.isEmpty() ? 0 : Integer.parseInt((String) values.get(0).get("detalles"));
      result.put("sala_espera", now().isBefore(releaseAt(c, result)));
      result.put(
          "liberacion_en",
          releaseAt(c, result).atZone(clock.getZone()).toOffsetDateTime().toString());
    } else result.put("sala_espera", false);
    result.put("agrupacion", grouped(call));
    result.put("tiene_prioridad", Db.number(result, "nivel_prioridad_calculado") > 1);
    result.put("numero_turno", assigned);
    result.put(
        "prioridad_aplicada",
        switch (Db.number(result, "nivel_prioridad_calculado")) {
          case 4 -> "Becario · puntaje de beca";
          case 3 -> "Público general · promedio anterior mayor a 9";
          case 2 -> "Alumno nuevo";
          default -> "Orden general";
        });
    result.put("folio_visible", result.get("folio"));
    result.put("estimacion_inicial", estimate == null);
    if (estimate == null)
      estimate =
          Math.max(0, Db.number(result, "posicion_actual") - 1) * Math.max(1, initialEstimate);
    result.put("espera_estimada_segundos", estimate);
    return result;
  }
}
