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
  @org.springframework.beans.factory.annotation.Autowired private CallSettings settings;
  @org.springframework.beans.factory.annotation.Autowired private java.time.Clock clock;
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

  public boolean eligible(int user, int call) {
    return !db.rows("SELECT id_usuario FROM usuarios WHERE id_usuario=? AND tipo_usuario='Comunidad IPN' AND estado_cuenta='Activa'",user).isEmpty()
        && "Solicitada".equals(settings.read("Solicitud beca · " + call + " · " + user).get("estado"));
  }

  @Transactional
  public void apply(int user, int call) {
    var c=db.one("SELECT * FROM convocatorias WHERE id_convocatoria=? FOR UPDATE",call);
    var u=db.one("SELECT * FROM usuarios WHERE id_usuario=? FOR UPDATE",user);
    if (!"Comunidad IPN".equals(u.get("tipo_usuario")) || !"Activa".equals(u.get("estado_cuenta")))
      throw new IllegalArgumentException("Solo alumnos IPN con cuenta verificada pueden solicitar beca.");
    if (List.of("Cerrada","Borrador").contains(c.get("estado")) || !java.time.LocalDateTime.now(clock).isBefore(QueueService.time(c.get("fecha_fin_fila"))))
      throw new IllegalArgumentException("Selecciona una convocatoria habilitada y vigente.");
    if (!db.rows("SELECT s.id_solicitud FROM solicitudes_inscripcion s JOIN grupos g ON g.id_grupo=s.id_grupo WHERE s.id_usuario=? AND g.id_convocatoria=? AND s.fecha_envio IS NOT NULL AND g.nivel<>'Colocación'",user,call).isEmpty())
      throw new IllegalArgumentException("Solicita la beca antes de enviar el comprobante de pago.");
    if(!eligible(user,call)) settings.write(user,"Solicitud beca · " + call + " · " + user,Map.of("estado","Solicitada","fecha",java.time.LocalDateTime.now(clock).toString()));
  }

  public List<Map<String,Object>> options(int user) {
    if(db.rows("SELECT id_usuario FROM usuarios WHERE id_usuario=? AND tipo_usuario='Comunidad IPN' AND estado_cuenta='Activa'",user).isEmpty())return List.of();
    var calls=db.rows("SELECT * FROM convocatorias WHERE estado NOT IN ('Cerrada','Borrador') AND fecha_fin_fila>? ORDER BY fecha_inicio_fila",java.time.LocalDateTime.now(clock));
    for(var call:calls)call.put("solicitada",eligible(user,Db.number(call,"id_convocatoria")));
    return calls;
  }

  public List<Map<String,Object>> applicants() {
    return db.rows("SELECT DISTINCT u.id_usuario,u.nombre_completo FROM usuarios u JOIN bitacora_acciones b ON b.id_usuario=u.id_usuario WHERE u.tipo_usuario='Comunidad IPN' AND u.estado_cuenta='Activa' AND b.accion LIKE 'Solicitud beca · %' ORDER BY u.nombre_completo");
  }

  public String status(int user, int call) {
    if (!eligible(user,call)) return "No beneficiario";
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
    if (!eligible(user,call)) throw new IllegalArgumentException("El dictamen de beca requiere alumno IPN verificado y solicitud de beca para esta convocatoria.");
    var requests =
        db.rows(
            "SELECT s.*,g.nivel AS nivel_grupo FROM solicitudes_inscripcion s JOIN grupos g ON"
                + " g.id_grupo=s.id_grupo WHERE s.id_usuario=? AND g.id_convocatoria=? FOR UPDATE",
            user,
            call);
    if (requests.stream().anyMatch(row -> !"Colocación".equals(row.get("nivel_grupo")) && !"Pendiente".equals(row.get("estado_inscripcion"))))
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
          "Convocatoria: "
              + c.get("nombre")
              + "\nResultado: "
              + status
              + "\n\n"
              + ("Beneficiario".equals(status)
                  ? "Tienes prioridad de becario en la fila y tu cuota del curso es $0.00. Puedes consultarlo en Mi perfil."
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
