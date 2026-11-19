package mx.ipn.esime.celex.service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import mx.ipn.esime.celex.repository.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AdministrativeExtras {
  private final Db db;
  private final CallSettings metadata;
  private final PrivateStorage storage;
  private final MailService mail;
  private final Clock clock;
  private static final Set<String> CLEANABLE =
      Set.of("Inicio de sesión", "Recuperación solicitada", "Código de activación reenviado");

  public AdministrativeExtras(
      Db db, CallSettings metadata, PrivateStorage storage, MailService mail, Clock clock) {
    this.db = db;
    this.metadata = metadata;
    this.storage = storage;
    this.mail = mail;
    this.clock = clock;
  }

  public Map<String, BigDecimal> fees(int user) {
    String type =
        (String)
            db.one("SELECT tipo_usuario FROM usuarios WHERE id_usuario=?", user)
                .get("tipo_usuario");
    var fees = new LinkedHashMap<String, BigDecimal>();
    fees.put("Historial académico", new BigDecimal("68.50"));
    fees.put("Constancia", new BigDecimal("34.50"));
    fees.put("Validación", BigDecimal.ZERO.setScale(2));
    fees.put("Diploma", new BigDecimal("Comunidad IPN".equals(type) ? "138.00" : "241.00"));
    return fees;
  }

  public List<Map<String, Object>> requests(Integer user) {
    var result = new ArrayList<Map<String, Object>>();
    for (var row :
        db.rows(
            "SELECT b.*,u.nombre_completo FROM bitacora_acciones b JOIN usuarios u ON"
                + " u.id_usuario=b.id_usuario WHERE b.accion='Solicitud de trámite' ORDER BY"
                + " b.id_bitacora DESC")) {
      if (user != null && Db.number(row, "id_usuario") != user) continue;
      int id = Db.number(row, "id_bitacora");
      var state = metadata.read("Trámite · " + id);
      state.put("id", id);
      state.put("usuario", row.get("id_usuario"));
      state.put("nombre", row.get("nombre_completo"));
      state.put("fecha", row.get("fecha_hora"));
      result.add(state);
    }
    return result;
  }

  public Map<String, Object> request(int id, int user, boolean finance) {
    return requests(finance ? null : user).stream()
        .filter(r -> Db.number(r, "id") == id)
        .findFirst()
        .orElseThrow(
            () ->
                new org.springframework.security.access.AccessDeniedException(
                    "Trámite no disponible"));
  }

  @Transactional(rollbackFor = Exception.class)
  public int submit(int user, String type, String reference, LocalDate paid, MultipartFile payment)
      throws Exception {
    var fee = fees(user).get(type);
    if (fee == null) throw new IllegalArgumentException("Trámite inválido.");
    if (paid == null
        || paid.isAfter(LocalDate.now(clock))
        || reference == null
        || reference.isBlank()
        || reference.length() > 50)
      throw new IllegalArgumentException("Revisa fecha y referencia.");
    String key = "";
    if (fee.signum() > 0) {
      if (payment == null || payment.isEmpty())
        throw new IllegalArgumentException("Adjunta comprobante de pago.");
      key = storage.save(payment);
    } else if (payment != null && !payment.isEmpty()) key = storage.save(payment);
    int id =
        db.id(
            "INSERT INTO bitacora_acciones(id_usuario,accion,detalles) VALUES(?,'Solicitud de"
                + " trámite',?)",
            user,
            type);
    var value = new LinkedHashMap<String, Object>();
    value.put("tipo", type);
    value.put("monto", fee.toPlainString());
    value.put("referencia", reference);
    value.put("fechaPago", paid.toString());
    value.put("archivo", key);
    value.put("estado", "Pendiente de revisión");
    value.put("observaciones", "");
    metadata.write(user, "Trámite · " + id, value);
    return id;
  }

  @Transactional
  public void review(int actor, int id, String status, String note) {
    if (!Set.of("Aprobado", "Rechazado").contains(status)
        || note.length() > 4000
        || status.equals("Rechazado") && note.isBlank())
      throw new IllegalArgumentException("Revisa dictamen y observaciones.");
    db.one(
        "SELECT id_bitacora FROM bitacora_acciones WHERE id_bitacora=? AND accion='Solicitud de"
            + " trámite' FOR UPDATE",
        id);
    var value = request(id, actor, true);
    if (!"Pendiente de revisión".equals(value.get("estado")))
      throw new IllegalArgumentException("El trámite ya fue revisado.");
    value.put("estado", status);
    value.put("observaciones", note);
    value.put("fechaDictamen", LocalDateTime.now(clock).toString());
    metadata.write(actor, "Trámite · " + id, value);
    String email =
        (String)
            db.one("SELECT correo FROM usuarios WHERE id_usuario=?", value.get("usuario"))
                .get("correo");
    mail.send(
        email, "CELEX · Dictamen de trámite", value.get("tipo") + ": " + status + ". " + note);
  }

  public boolean cleanable(String action) {
    return CLEANABLE.contains(action);
  }

  @Transactional
  public void clearActivities(int actor, List<Integer> ids) {
    if (ids == null || ids.isEmpty() || ids.size() > 200)
      throw new IllegalArgumentException("Selecciona entre 1 y 200 actividades de acceso.");
    var unique = new TreeSet<Integer>(ids);
    for (int id : unique) {
      var row = db.one("SELECT accion FROM bitacora_acciones WHERE id_bitacora=? FOR UPDATE", id);
      if (!cleanable((String) row.get("accion")))
        throw new IllegalArgumentException(
            "Los movimientos académicos, financieros y de configuración están protegidos.");
    }
    for (int id : unique) db.update("DELETE FROM bitacora_acciones WHERE id_bitacora=?", id);
    db.audit(actor, "Eliminación de actividades de acceso", unique.toString());
  }
}
