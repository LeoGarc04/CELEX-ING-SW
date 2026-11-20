package mx.ipn.esime.celex.service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import mx.ipn.esime.celex.repository.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PaymentService {
  private final Db db;
  private final PrivateStorage storage;
  private final EnrollmentService enrollments;
  private final ReceiptService receipts;
  private final MailService mail;
  private final BusinessCalendar calendar;
  @org.springframework.beans.factory.annotation.Value("${celex.public-url}") private String publicUrl;
  private final Clock clock;
  private final ScholarshipService scholarships;
  @org.springframework.beans.factory.annotation.Autowired private CallSettings settings;

  public PaymentService(
      Db db,
      PrivateStorage storage,
      EnrollmentService enrollments,
      ReceiptService receipts,
      MailService mail,
      Clock clock,
      ScholarshipService scholarships,
      BusinessCalendar calendar) {
    this.calendar = calendar;
    this.scholarships = scholarships;
    this.db = db;
    this.storage = storage;
    this.enrollments = enrollments;
    this.receipts = receipts;
    this.mail = mail;
    this.clock = clock;
  }

  @Transactional(rollbackFor = Exception.class)
  public void submit(
      int user,
      int id,
      String reference,
      LocalDate date,
      BigDecimal amount,
      MultipartFile payment,
      MultipartFile identity)
      throws Exception {
    var s =
        db.one(
            "SELECT * FROM solicitudes_inscripcion WHERE id_solicitud=? AND id_usuario=? FOR"
                + " UPDATE",
            id,
            user);
    if (!List.of("Pendiente", "En proceso").contains(s.get("estado_inscripcion")))
      throw new IllegalArgumentException("La solicitud no admite documentos en su estado actual.");
    if (s.get("prorroga_expira_en") != null
        && !QueueService.time(s.get("prorroga_expira_en")).isAfter(LocalDateTime.now(clock)))
      throw new IllegalArgumentException("La prórroga venció.");
    if (reference == null || reference.isBlank()) reference = "ENVIO-" + UUID.randomUUID();
    if (reference.length() > 50
        || date.isAfter(LocalDate.now(clock))
        || amount.compareTo((BigDecimal) s.get("monto_calculado")) != 0)
      throw new IllegalArgumentException(
          "Revisa la fecha y el importe exacto de la cuota.");
    var pref=settings.preference(id);
    if("Colocación".equals(pref.get("via")) && Objects.toString(pref.get("nivelSolicitado"),"").isBlank())
      throw new IllegalArgumentException("Selecciona el nivel para el que solicitas el examen antes de enviar el pago.");
    boolean paymentOnly = !pref.isEmpty();
    if (!paymentOnly && (identity == null || identity.isEmpty()))
      throw new IllegalArgumentException("Adjunta identificación.");
    String p = storage.save(payment), i = null;
    try {
      i = paymentOnly ? "" : storage.save(identity);
      db.update(
          "INSERT INTO"
              + " documentos_comprobantes(id_solicitud,numero_referencia,fecha_pago,importe,url_comprobante_pago,url_identificacion,estatus_pago,observaciones_dictamen)"
              + " VALUES(?,?,?,?,?,?,'Pendiente de revisión',?)",
          id,
          reference,
          date,
          amount,
          p,
          i,
          (paymentOnly
                  ? new DocumentAssessment(
                      "Pendiente de revisión", "", "Aprobado", "No requerido en este flujo")
                  : DocumentAssessment.pending())
              .encode());
      db.update(
          "UPDATE solicitudes_inscripcion SET estado_inscripcion='Pendiente de"
              + " revisión',fecha_envio=?,prorroga_expira_en=NULL WHERE id_solicitud=?",
          LocalDateTime.now(clock),
          id);
      db.audit(user, "Envío a revisión", "Solicitud " + id);
    } catch (Exception e) {
      storage.remove(p);
      if (i != null && !i.isBlank()) storage.remove(i);
      throw e;
    }
  }

  @Transactional(rollbackFor = Exception.class)
  public void review(int admin, int id, String decision, String note) throws Exception {
    switch (decision) {
      case "Aprobado" ->
          reviewDocuments(admin, id, new DocumentAssessment("Aprobado", note, "Aprobado", note));
      case "Correccion", "Rechazado" ->
          reviewDocuments(admin, id, new DocumentAssessment("Rechazado", note, "Rechazado", note));
      default -> throw new IllegalArgumentException("Dictamen inválido");
    }
  }

  @Transactional(rollbackFor = Exception.class)
  public void reviewDocuments(int admin, int id, DocumentAssessment assessment) throws Exception {
    var s = db.one("SELECT * FROM solicitudes_inscripcion WHERE id_solicitud=? FOR UPDATE", id);
    if (!"Pendiente de revisión".equals(s.get("estado_inscripcion")))
      throw new IllegalArgumentException("La solicitud no está pendiente de revisión.");
    if (assessment.payment().equals("Pendiente de revisión")
        || assessment.identity().equals("Pendiente de revisión"))
      throw new IllegalArgumentException(
          "Dictamina ambos documentos antes de terminar la revisión.");
    if (assessment.payment().equals("Rechazado") && assessment.paymentNote().isBlank()
        || assessment.identity().equals("Rechazado") && assessment.identityNote().isBlank())
      throw new IllegalArgumentException("Indica el motivo de cada documento rechazado.");
    var documents =
        db.rows(
            "SELECT * FROM documentos_comprobantes WHERE id_solicitud=? ORDER BY id_documento DESC",
            id);
    if (documents.isEmpty()) throw new IllegalArgumentException("Faltan documentos.");
    var now = LocalDateTime.now(clock);
    String result;
    if (assessment.approved()) {
      if (!settings.preference(id).isEmpty() && !settings.assigned(id))
        throw new IllegalArgumentException(
            "Asigna el grupo, horario y profesor antes de aprobar la inscripción.");
      var preference=settings.preference(id);
      if(preference.containsKey("origenExamen") && settings.classes(((Number)preference.get("convocatoria")).intValue())==null)
        throw new IllegalArgumentException("Configura el inicio de clases de la convocatoria antes de confirmar el curso.");
      result = "Confirmada";
      db.update(
          "UPDATE solicitudes_inscripcion SET"
              + " estado_inscripcion='Confirmada',prorroga_expira_en=NULL WHERE id_solicitud=?",
          id);
      receipts.issue(id);
    } else {
      result = "Corrección requerida";
      db.update(
          "UPDATE solicitudes_inscripcion SET estado_inscripcion='En proceso',prorroga_expira_en=?"
              + " WHERE id_solicitud=?",
          calendar.correctionDeadline(now),
          id);
    }
    db.update(
        "UPDATE documentos_comprobantes SET"
            + " estatus_pago=?,observaciones_dictamen=?,validado_por=?,fecha_dictamen=? WHERE"
            + " id_documento=?",
        assessment.payment(),
        assessment.encode(),
        admin,
        now,
        documents.get(0).get("id_documento"));
    db.audit(
        admin, "Dictamen de solicitud", id + " · " + result + " · " + assessment.observations());
    String email =
        (String)
            db.one("SELECT correo FROM usuarios WHERE id_usuario=?", s.get("id_usuario"))
                .get("correo");
    mail.send(
        email,
        "CELEX · Dictamen de inscripción",
        "Folio: " + s.get("folio")
            + "\nEstado: " + result
            + "\n\nObservaciones\n" + assessment.observations()
            + (assessment.rejected()
                ? "\n\nCorrección del comprobante\nTienes 48 horas desde este rechazo para corregir.\nFecha límite: "
                    + calendar.correctionDeadline(now).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    + "\n\nAbre tu solicitud para adjuntar la corrección:\n" + publicUrl + "/solicitud/" + id
                : "\n\nTu inscripción está confirmada.\nDescarga tu comprobante oficial con QR:\n" + publicUrl + "/solicitud/" + id + "/comprobante"
                    + "\n\nConsulta tu grupo, horario y profesor:\n" + publicUrl + "/solicitud/" + id));
  }

  public void scholarship(int admin, int id, String status) {
    var s =
        db.one(
            "SELECT s.id_usuario,g.id_convocatoria FROM solicitudes_inscripcion s JOIN grupos g ON"
                + " g.id_grupo=s.id_grupo WHERE s.id_solicitud=?",
            id);
    scholarships.record(
        admin,
        Db.number(s, "id_usuario"),
        Db.number(s, "id_convocatoria"),
        status,
        "Dictamen desde la solicitud " + id);
  }
}
