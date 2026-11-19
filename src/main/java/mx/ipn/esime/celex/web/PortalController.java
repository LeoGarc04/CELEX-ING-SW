package mx.ipn.esime.celex.web;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import mx.ipn.esime.celex.repository.Db;
import mx.ipn.esime.celex.service.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Controller
public class PortalController {
  private final Db db;
  private final QueueService queue;
  private final EnrollmentService enrollments;
  private final PaymentService payments;
  private final PrivateStorage storage;
  private final String publicUrl;
  @org.springframework.beans.factory.annotation.Autowired private CallSettings settings;
  @org.springframework.beans.factory.annotation.Autowired private IntakeService intake;
  @org.springframework.beans.factory.annotation.Autowired private java.time.Clock clock;

  public PortalController(
      Db db,
      QueueService queue,
      EnrollmentService enrollments,
      PaymentService payments,
      PrivateStorage storage,
      @org.springframework.beans.factory.annotation.Value("${celex.public-url}") String publicUrl) {
    this.publicUrl = publicUrl;
    this.db = db;
    this.queue = queue;
    this.enrollments = enrollments;
    this.payments = payments;
    this.storage = storage;
  }

  public int user(Authentication a) {
    return Db.number(
        db.one(
            "SELECT id_usuario FROM usuarios WHERE correo=? AND estado_cuenta='Activa'",
            a.getName()),
        "id_usuario");
  }

  @GetMapping("/panel")
  String panel(Authentication a, Model m) {
    if (a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ADMIN")))
      return "redirect:/admin";
    if (a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("PROFESOR")))
      return "redirect:/profesor";
    int id = user(a);
    m.addAttribute(
        "account",
        db.one(
            "SELECT nombre_completo,tipo_usuario,estado_cuenta FROM usuarios WHERE id_usuario=?",
            id));
    m.addAttribute(
        "calls",
        db
            .rows(
                "SELECT * FROM convocatorias WHERE estado NOT IN ('Cerrada','Borrador') AND"
                    + " fecha_fin_fila>? ORDER BY fecha_inicio_fila",
                LocalDateTime.now(clock))
            .stream()
            .map(
                c -> {
                  c.put("estado_visible", queue.status(c));
                  c.put("nuevoFlujo", settings.managed(Db.number(c, "id_convocatoria")));
                  return c;
                })
            .toList());
    m.addAttribute(
        "turns",
        db.rows(
            "SELECT f.* FROM fila_virtual f JOIN convocatorias c ON"
                + " c.id_convocatoria=f.id_convocatoria WHERE f.id_usuario=? AND c.estado NOT IN"
                + " ('Cerrada','Borrador') AND c.fecha_fin_fila>? ORDER BY f.id_turno DESC",
            id,
            LocalDateTime.now(clock)));
    m.addAttribute(
        "requests",
        db.rows(
            "SELECT s.*,g.nivel,g.horario,g.profesor,g.modalidad,g.aula,g.id_convocatoria,i.nombre"
                + " AS idioma FROM solicitudes_inscripcion s JOIN grupos g ON g.id_grupo=s.id_grupo"
                + " JOIN idiomas i ON i.id_idioma=g.id_idioma WHERE s.id_usuario=? ORDER BY"
                + " id_solicitud DESC",
            id));
    return "panel";
  }

  @PostMapping("/fila")
  String join(
      Authentication a, @RequestParam int convocatoria, jakarta.servlet.http.HttpSession session) {
    String folio = queue.join(user(a), convocatoria);
    session.setAttribute("queue.folio", folio);
    return "redirect:/fila/" + folio;
  }

  private Integer existingRequest(int user, String folio) {
    if (db.rows("SELECT id_turno FROM fila_virtual WHERE folio=? AND id_usuario=?", folio, user).isEmpty())
      throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
    var requests = db.rows(
        "SELECT s.id_solicitud FROM solicitudes_inscripcion s JOIN fila_virtual f ON f.folio=s.folio"
            + " AND f.id_usuario=s.id_usuario WHERE s.folio=? AND s.id_usuario=? ORDER BY"
            + " s.id_solicitud DESC LIMIT 1", folio, user);
    return requests.isEmpty() ? null : Db.number(requests.get(0), "id_solicitud");
  }

  @GetMapping("/fila/{folio}")
  String queue(
      Authentication a,
      @PathVariable String folio,
      Model m,
      jakarta.servlet.http.HttpSession session) {
    Integer request = existingRequest(user(a), folio);
    if (request != null) {
      session.removeAttribute("queue.folio");
      return "redirect:/solicitud/" + request;
    }
    var turn = queue.heartbeat(user(a), folio);
    m.addAttribute("turn", turn);
    m.addAttribute(
        "queueFocused", List.of("En espera", "Atendiendo").contains(turn.get("estado_turno")));
    m.addAttribute("focusedQueueLink", "/fila/" + folio);
    session.setAttribute("queue.folio", folio);
    return "fila";
  }

  @PostMapping("/fila/{folio}/latido")
  @ResponseBody
  Map<String, Object> heartbeat(
      Authentication a, @PathVariable String folio, jakarta.servlet.http.HttpSession session) {
    Integer request = existingRequest(user(a), folio);
    if (request != null) {
      session.removeAttribute("queue.folio");
      return Map.of("solicitud_url", "/solicitud/" + request);
    }
    var turn = queue.heartbeat(user(a), folio);
    session.setAttribute("queue.folio", folio);
    return turn;
  }

  @GetMapping("/fila/{folio}/grupos")
  String groups(Authentication a, @PathVariable String folio, Model m) {
    Integer request = existingRequest(user(a), folio);
    if (request != null) return "redirect:/solicitud/" + request;
    var turn = queue.heartbeat(user(a), folio);
    if (!"Atendiendo".equals(turn.get("estado_turno")))
      throw new IllegalArgumentException("Espera a que llegue tu turno.");
    m.addAttribute("turn", turn);
    m.addAttribute("queueFocused", true);
    m.addAttribute("focusedQueueLink", "/fila/" + folio);
    var offers = enrollments.offers(user(a), Db.number(turn, "id_convocatoria"));
    m.addAttribute("groups", offers);
    if (settings.managed(Db.number(turn, "id_convocatoria"))) {
      m.addAttribute("options", intake.options(user(a), Db.number(turn, "id_convocatoria")));
      return "preferencias";
    }
    m.addAttribute(
        "availableLanguages",
        offers.stream()
            .map(g -> g.get("idioma"))
            .distinct()
            .sorted()
            .map(name -> java.util.Map.of("nombre", name))
            .toList());
    return "grupos";
  }

  @GetMapping("/fila/{folio}/oferta")
  @ResponseBody
  List<Map<String, Object>> currentOffers(Authentication a, @PathVariable String folio) {
    int id = user(a);
    if (db.rows("SELECT id_turno FROM fila_virtual WHERE folio=? AND id_usuario=?", folio, id)
        .isEmpty())
      throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
    try {
      var turn = queue.heartbeat(id, folio);
      if (!"Atendiendo".equals(turn.get("estado_turno")))
        throw new IllegalArgumentException("Espera tu turno para consultar la oferta.");
      return enrollments.offers(id, Db.number(turn, "id_convocatoria"));
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          HttpStatus.CONFLICT, e.getMessage());
    }
  }

  @PostMapping("/fila/{folio}/grupo")
  String select(
      Authentication a,
      @PathVariable String folio,
      @RequestParam int grupo,
      jakarta.servlet.http.HttpSession session) {
    var originalTurn =
        db.one(
            "SELECT id_convocatoria FROM fila_virtual WHERE folio=? AND id_usuario=?",
            folio,
            user(a));
    if (settings.managed(Db.number(originalTurn, "id_convocatoria")))
      throw new IllegalArgumentException(
          "Usa la selección de idioma, modalidad y horario del nuevo proceso.");
    int id = enrollments.select(user(a), folio, grupo);
    session.removeAttribute("queue.folio");
    return "redirect:/solicitud/" + id;
  }

  @GetMapping("/solicitud/{id}")
  String request(Authentication a, @PathVariable int id, Model m) {
    m.addAttribute(
        "request",
        db.one(
            "SELECT s.*,g.nivel,g.horario,g.profesor,g.modalidad,g.aula,g.id_convocatoria,i.nombre"
                + " AS idioma FROM solicitudes_inscripcion s JOIN grupos g ON g.id_grupo=s.id_grupo"
                + " JOIN idiomas i ON i.id_idioma=g.id_idioma WHERE s.id_solicitud=? AND"
                + " s.id_usuario=?",
            id,
            user(a)));
    m.addAttribute(
        "documents",
        DocumentAssessment.display(
            db.rows(
                "SELECT * FROM documentos_comprobantes WHERE id_solicitud=? ORDER BY id_documento"
                    + " DESC",
                id)));
    var preference = settings.preference(id);
    if (!preference.isEmpty()) {
      m.addAttribute("preference", preference);
      m.addAttribute("assignment", settings.assignment(id));
      @SuppressWarnings("unchecked")
      var current = (Map<String, Object>) m.asMap().get("request");
      m.addAttribute(
          "reviewDeadline",
          current.get("fecha_envio") == null
              ? null
              : QueueService.time(current.get("fecha_envio")).plusHours(48));
      return "solicitud-v2";
    }
    return "solicitud";
  }

  @PostMapping("/solicitud/{id}/documentos")
  String upload(
      Authentication a,
      @PathVariable int id,
      @RequestParam String referencia,
      @RequestParam LocalDate fecha,
      @RequestParam BigDecimal importe,
      @RequestParam MultipartFile pago,
      @RequestParam(required = false) MultipartFile identificacion)
      throws Exception {
    payments.submit(user(a), id, referencia, fecha, importe, pago, identificacion);
    return "redirect:/solicitud/" + id;
  }

  @PostMapping("/solicitud/{id}/cancelar")
  String cancel(Authentication a, @PathVariable int id) {
    enrollments.cancel(user(a), id);
    return "redirect:/panel";
  }

  @GetMapping("/documentos/{id}/{kind}")
  ResponseEntity<byte[]> document(Authentication a, @PathVariable int id, @PathVariable String kind)
      throws Exception {
    var d =
        db.one(
            "SELECT d.*,s.id_usuario FROM documentos_comprobantes d JOIN solicitudes_inscripcion s"
                + " ON s.id_solicitud=d.id_solicitud WHERE d.id_documento=?",
            id);
    boolean financial =
        a.getAuthorities().stream().anyMatch(x -> Set.of("FINANCIERO").contains(x.getAuthority()));
    if (Db.number(d, "id_usuario") != user(a) && !financial)
      throw new org.springframework.security.access.AccessDeniedException("Sin permiso");
    String column =
        switch (kind) {
          case "pago" -> "url_comprobante_pago";
          case "identificacion" -> "url_identificacion";
          default -> throw new IllegalArgumentException("Documento no disponible");
        };
    return file((String) d.get(column));
  }

  @GetMapping("/solicitud/{id}/qr")
  ResponseEntity<byte[]> receiptQr(Authentication a, @PathVariable int id) throws Exception {
    var rows =
        db.rows(
            "SELECT c.codigo_qr FROM comprobantes_oficiales c JOIN solicitudes_inscripcion s ON"
                + " s.id_solicitud=c.id_solicitud WHERE s.id_solicitud=? AND s.id_usuario=? AND"
                + " s.estado_inscripcion='Confirmada'",
            id,
            user(a));
    if (rows.isEmpty())
      throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
    String url = publicUrl + "/validar/" + rows.get(0).get("codigo_qr");
    var matrix =
        new com.google.zxing.qrcode.QRCodeWriter()
            .encode(url, com.google.zxing.BarcodeFormat.QR_CODE, 240, 240);
    var bytes = new java.io.ByteArrayOutputStream();
    com.google.zxing.client.j2se.MatrixToImageWriter.writeToStream(matrix, "PNG", bytes);
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_PNG)
        .cacheControl(CacheControl.noStore())
        .body(bytes.toByteArray());
  }

  @GetMapping("/solicitud/{id}/comprobante")
  ResponseEntity<byte[]> receipt(Authentication a, @PathVariable int id) throws Exception {
    var r =
        db.one(
            "SELECT c.url_pdf FROM comprobantes_oficiales c JOIN solicitudes_inscripcion s ON"
                + " s.id_solicitud=c.id_solicitud WHERE s.id_solicitud=? AND s.id_usuario=? AND"
                + " s.estado_inscripcion='Confirmada'",
            id,
            user(a));
    return file((String) r.get("url_pdf"));
  }

  private ResponseEntity<byte[]> file(String key) throws Exception {
    String type =
        key.endsWith("pdf") ? "application/pdf" : key.endsWith("png") ? "image/png" : "image/jpeg";
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(type))
        .cacheControl(CacheControl.noStore())
        .header(
            "Content-Disposition",
            "inline; filename=documento." + key.substring(key.lastIndexOf('.') + 1))
        .body(storage.read(key));
  }

  @GetMapping("/validar/{token}")
  String validate(@PathVariable String token, Model m) {
    m.addAttribute(
        "receipts",
        db.rows(
            "SELECT s.folio,s.estado_inscripcion,g.nivel,i.nombre AS idioma,c.fecha_emision FROM"
                + " comprobantes_oficiales c JOIN solicitudes_inscripcion s ON"
                + " s.id_solicitud=c.id_solicitud JOIN grupos g ON g.id_grupo=s.id_grupo JOIN"
                + " idiomas i ON i.id_idioma=g.id_idioma WHERE c.codigo_qr=?",
            token));
    return "validar";
  }
}
