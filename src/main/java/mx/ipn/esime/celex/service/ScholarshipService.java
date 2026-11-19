package mx.ipn.esime.celex.service;

import java.util.*;
import mx.ipn.esime.celex.repository.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ScholarshipService {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Set<String> STATES = Set.of("Pendiente", "Beneficiario", "No beneficiario");
  private final Db db;
  private final FeePolicy fees;
  @org.springframework.beans.factory.annotation.Autowired private MailService mail;

  public ScholarshipService(Db db, FeePolicy fees) {
    this.db = db;
    this.fees = fees;
  }

  private static String action(int user, int call) {
    return "Beca previa · " + call + " · " + user;
  }

  public String status(int user, int call) {
    var events =
        db.rows(
            "SELECT detalles FROM bitacora_acciones WHERE accion=? ORDER BY id_bitacora DESC LIMIT"
                + " 1",
            action(user, call));
    if (events.isEmpty()) return "Pendiente";
    try {
      String status =
          JSON.readTree((String) events.get(0).get("detalles")).path("estado").asString();
      if (STATES.contains(status)) return status;
    } catch (RuntimeException ignored) {
    }
    throw new IllegalArgumentException(
        "El dictamen previo de beca requiere revisión administrativa.");
  }

  @Transactional
  public void record(int actor, int user, int call, String status, String note) {
    if (!STATES.contains(status) || note.length() > 4000)
      throw new IllegalArgumentException("Estado u observaciones de beca inválidos");
    var c = db.one("SELECT * FROM convocatorias WHERE id_convocatoria=? FOR UPDATE", call);
    var u = db.one("SELECT * FROM usuarios WHERE id_usuario=? FOR UPDATE", user);
    var requests =
        db.rows(
            "SELECT s.*,g.nivel AS nivel_grupo FROM solicitudes_inscripcion s JOIN grupos g ON"
                + " g.id_grupo=s.id_grupo WHERE s.id_usuario=? AND g.id_convocatoria=? FOR UPDATE",
            user,
            call);
    if (requests.stream().anyMatch(row -> !"Pendiente".equals(row.get("estado_inscripcion"))))
      throw new IllegalArgumentException(
          "La beca no puede modificarse después del envío de documentos. Revisa el caso con"
              + " Finanzas.");
    String previous = status(user, call);
    var event = new LinkedHashMap<String, Object>();
    event.put("version", 1);
    event.put("usuario", user);
    event.put("nombre", u.get("nombre_completo"));
    event.put("convocatoria", call);
    event.put("convocatoriaNombre", c.get("nombre"));
    event.put("estado", status);
    event.put("observaciones", note);
    db.audit(actor, action(user, call), JSON.writeValueAsString(event));
    if (!status.equals(previous))
      mail.send(
          (String) u.get("correo"),
          "CELEX · Resultado de beca",
          "Convocatoria "
              + c.get("nombre")
              + ": "
              + status
              + ". "
              + ("Beneficiario".equals(status)
                  ? "Tu cuota del curso es $0.00. Puedes consultarlo en Mi perfil."
                  : "Consulta el resultado y tu cuota en Mi perfil."));
    for (var request : requests)
      db.update(
          "UPDATE solicitudes_inscripcion SET estado_beca=?,monto_calculado=? WHERE id_solicitud=?",
          status,
          "Colocación".equals(request.get("nivel_grupo"))
              ? new java.math.BigDecimal("150.00")
              : fees.calculate((String) u.get("tipo_usuario"), status),
          request.get("id_solicitud"));
  }

  public List<Map<String, Object>> recent() {
    return display(
        db.rows(
            "SELECT * FROM bitacora_acciones WHERE accion LIKE 'Beca previa · %' ORDER BY"
                + " id_bitacora DESC LIMIT 1000"));
  }

  public List<Map<String, Object>> forUser(int user) {
    return display(
        db.rows(
            "SELECT * FROM bitacora_acciones WHERE accion LIKE ? ORDER BY id_bitacora DESC",
            "Beca previa · % · " + user));
  }

  private List<Map<String, Object>> display(List<Map<String, Object>> rows) {
    var latest = new LinkedHashMap<String, Map<String, Object>>();
    for (var row : rows) {
      String key = (String) row.get("accion");
      if (latest.containsKey(key)) continue;
      try {
        var tree = JSON.readTree((String) row.get("detalles"));
        if (tree.path("version").asInt() != 1) continue;
        var display = new HashMap<String, Object>();
        display.put("id_usuario", tree.path("usuario").asInt());
        display.put("id_convocatoria", tree.path("convocatoria").asInt());
        display.put("usuario", tree.path("nombre").asString());
        display.put("convocatoria", tree.path("convocatoriaNombre").asString());
        display.put("estado", tree.path("estado").asString());
        display.put("observaciones", tree.path("observaciones").asString(""));
        display.put("fecha", row.get("fecha_hora"));
        latest.put(key, display);
      } catch (RuntimeException ignored) {
      }
    }
    return new ArrayList<>(latest.values());
  }
}
