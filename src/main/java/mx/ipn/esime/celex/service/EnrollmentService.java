package mx.ipn.esime.celex.service;

import java.math.BigDecimal;
import java.util.*;
import mx.ipn.esime.celex.repository.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnrollmentService {
  private final Db db;
  private final QueueService queue;
  private final FeePolicy fees;
  private final ScholarshipService scholarships;
  @org.springframework.beans.factory.annotation.Autowired private CallSettings settings;
  @org.springframework.beans.factory.annotation.Autowired private AcademicRoutes routes;

  public EnrollmentService(
      Db db, QueueService queue, FeePolicy fees, ScholarshipService scholarships) {
    this.db = db;
    this.queue = queue;
    this.fees = fees;
    this.scholarships = scholarships;
  }

  public List<Map<String, Object>> offers(int user, int call) {
    queue.requireOpen(db.one("SELECT * FROM convocatorias WHERE id_convocatoria=?", call));
    return db
        .rows(
            "SELECT g.*,i.nombre AS idioma FROM grupos g JOIN idiomas i ON i.id_idioma=g.id_idioma"
                + " WHERE g.id_convocatoria=? AND g.estado='Activo' AND i.activo=TRUE ORDER BY"
                + " i.nombre,g.nivel,g.horario",
            call)
        .stream()
        .filter(g -> canEnroll(user, g))
        .map(
            g -> {
              var offer = new HashMap<String, Object>(g);
              offer.put("compatible", true);
              return (Map<String, Object>) offer;
            })
        .toList();
  }

  public boolean canEnroll(int user, Map<String, Object> group) {
    if (settings.managed(Db.number(group, "id_convocatoria"))) return routes.eligible(user, group);
    var history =
        db.rows(
            "SELECT * FROM historial_academico WHERE id_usuario=? AND id_idioma=? ORDER BY"
                + " fecha_registro DESC,id_historial DESC",
            user,
            group.get("id_idioma"));
    return !history.isEmpty()
        && Rules.eligible(history.get(0))
        && Objects.equals(history.get(0).get("nivel_actual"), group.get("nivel"));
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public int select(int user, String folio, int groupId) {
    var turn = db.one("SELECT * FROM fila_virtual WHERE folio=? AND id_usuario=?", folio, user);
    int call = Db.number(turn, "id_convocatoria");
    var c = queue.lock(call);
    queue.requireOpen(c);
    queue.expire(call);
    queue.order(call);
    var existing = db.rows("SELECT id_solicitud FROM solicitudes_inscripcion WHERE folio=?", folio);
    if (!existing.isEmpty()) return Db.number(existing.get(0), "id_solicitud");
    turn = db.one("SELECT * FROM fila_virtual WHERE folio=? AND id_usuario=?", folio, user);
    if (!List.of("En espera", "Atendiendo").contains(turn.get("estado_turno"))
        || Db.number(turn, "posicion_actual") != 1
        || !queue.ready(turn))
      throw new IllegalArgumentException("Todavía no es tu turno o tu reserva caducó.");
    var g =
        db.one(
            "SELECT g.*,i.activo AS idioma_activo FROM grupos g JOIN idiomas i ON"
                + " i.id_idioma=g.id_idioma WHERE g.id_grupo=? FOR UPDATE",
            groupId);
    if (Db.number(g, "id_convocatoria") != call
        || !"Activo".equals(g.get("estado"))
        || !Rules.yes(g.get("idioma_activo"))
        || !canEnroll(user, g))
      throw new IllegalArgumentException(
          "Grupo no compatible con tu nivel, historial o convocatoria.");
    var u = db.one("SELECT * FROM usuarios WHERE id_usuario=?", user);
    String scholarship = scholarships.status(user, call);
    BigDecimal amount = fees.calculate((String) u.get("tipo_usuario"), scholarship);
    if (db.update(
            "UPDATE grupos SET cupo_disponible=cupo_disponible-1 WHERE id_grupo=? AND"
                + " cupo_disponible>0",
            groupId)
        != 1) throw new IllegalArgumentException("El grupo se agotó. Elige otro.");
    int id =
        db.id(
            "INSERT INTO"
                + " solicitudes_inscripcion(folio,id_usuario,id_grupo,estado_inscripcion,monto_calculado,estado_beca)"
                + " VALUES(?,?,?,'Pendiente',?,?)",
            folio,
            user,
            groupId,
            amount,
            scholarship);
    db.update(
        "UPDATE fila_virtual SET estado_turno='Inscrito',posicion_actual=0,reserva_expira_en=NULL"
            + " WHERE folio=?",
        folio);
    queue.order(call);
    db.audit(user, "Selección de grupo", folio + " · grupo " + groupId);
    return id;
  }

  public BigDecimal fee(String type) {
    return fees.full(type);
  }

  public void release(Map<String, Object> s, String state) {
    if (List.of("Confirmada", "Cancelada", "Rechazada").contains(s.get("estado_inscripcion")))
      throw new IllegalArgumentException("La solicitud ya tiene dictamen final.");
    db.update(
        "UPDATE solicitudes_inscripcion SET estado_inscripcion=?,prorroga_expira_en=NULL WHERE"
            + " id_solicitud=?",
        state,
        s.get("id_solicitud"));
    db.update(
        "UPDATE grupos SET cupo_disponible=cupo_disponible+1 WHERE id_grupo=? AND"
            + " cupo_disponible<cupo_total",
        s.get("id_grupo"));
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public void cancel(int user, int id) {
    var s =
        db.one(
            "SELECT * FROM solicitudes_inscripcion WHERE id_solicitud=? AND id_usuario=? FOR"
                + " UPDATE",
            id,
            user);
    release(s, "Cancelada");
    db.audit(user, "Cancelación de solicitud", "Solicitud " + id);
  }
}
