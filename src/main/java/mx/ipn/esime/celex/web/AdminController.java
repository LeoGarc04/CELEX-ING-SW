package mx.ipn.esime.celex.web;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import mx.ipn.esime.celex.repository.Db;
import mx.ipn.esime.celex.service.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin")
public class AdminController {
  private final Db db;
  private final AdminService admin;
  private final PaymentService payments;
  private final PortalController portal;
  private final BackupCatalog backups;
  private final ScholarshipService scholarships;
  private final TeacherService teacherService;

  public AdminController(
      Db db,
      AdminService admin,
      PaymentService payments,
      PortalController portal,
      BackupCatalog backups,
      ScholarshipService scholarships,
      TeacherService teacherService) {
    this.teacherService = teacherService;
    this.scholarships = scholarships;
    this.backups = backups;
    this.db = db;
    this.admin = admin;
    this.payments = payments;
    this.portal = portal;
  }

  private boolean allowed(Authentication a, String p) {
    return a.getAuthorities().stream()
        .anyMatch(x -> x.getAuthority().equals("ADMIN") || x.getAuthority().equals(p));
  }

  @GetMapping
  String admin(Authentication a, Model m) throws java.io.IOException {
    m.addAttribute("config", allowed(a, "ADMIN"));
    m.addAttribute("academic", allowed(a, "ADMIN"));
    m.addAttribute("financial", allowed(a, "ADMIN"));
    m.addAttribute("auditor", allowed(a, "ADMIN"));
    m.addAttribute("manager", allowed(a, "ADMIN"));
    m.addAttribute(
        "calls",
        (allowed(a, "ADMIN") || allowed(a, "ADMIN") || allowed(a, "ADMIN"))
            ? db.rows("SELECT * FROM convocatorias ORDER BY id_convocatoria DESC")
            : List.of());
    m.addAttribute("languages", allowed(a, "ADMIN") ? db.rows("SELECT * FROM idiomas") : List.of());
    m.addAttribute(
        "groups",
        allowed(a, "ADMIN") ? db.rows("SELECT * FROM grupos ORDER BY id_grupo DESC") : List.of());
    m.addAttribute(
        "users",
        allowed(a, "ADMIN") || allowed(a, "ADMIN") || allowed(a, "ADMIN")
            ? db.rows(
                "SELECT id_usuario,nombre_completo,correo,tipo_usuario FROM usuarios ORDER BY"
                    + " id_usuario DESC")
            : List.of());
    m.addAttribute(
        "requests",
        allowed(a, "ADMIN")
            ? db.rows(
                "SELECT s.*,u.nombre_completo FROM solicitudes_inscripcion s JOIN usuarios u ON"
                    + " u.id_usuario=s.id_usuario ORDER BY id_solicitud DESC")
            : List.of());
    m.addAttribute(
        "logs",
        allowed(a, "ADMIN")
            ? db
                .rows("SELECT * FROM bitacora_acciones ORDER BY id_bitacora DESC LIMIT 200")
                .stream()
                .map(
                    row -> {
                      if (Objects.toString(row.get("accion"), "").startsWith("Beca previa · ")) {
                        var copy = new HashMap<>(row);
                        try {
                          var t =
                              tools.jackson.databind.json.JsonMapper.builder()
                                  .build()
                                  .readTree((String) row.get("detalles"));
                          copy.put(
                              "detalles",
                              t.path("nombre").asString()
                                  + " · "
                                  + t.path("convocatoriaNombre").asString()
                                  + " · "
                                  + t.path("estado").asString()
                                  + " · "
                                  + t.path("observaciones").asString(""));
                        } catch (RuntimeException ignored) {
                        }
                        return copy;
                      }
                      return row;
                    })
                .toList()
            : List.of());
    boolean auditor = allowed(a, "ADMIN");
    m.addAttribute("backupEnabled", auditor && backups.enabled());
    m.addAttribute("backups", auditor ? backups.list() : List.of());
    m.addAttribute("reportRequests", auditor ? reportRequests() : List.of());
    m.addAttribute("reportGroups", auditor ? reportGroups() : List.of());
    m.addAttribute("scholarships", allowed(a, "ADMIN") ? scholarships.recent() : List.of());
    m.addAttribute(
        "histories",
        allowed(a, "ADMIN")
            ? db
                .rows(
                    "SELECT h.*,u.nombre_completo,i.nombre AS idioma FROM historial_academico h"
                        + " JOIN usuarios u ON u.id_usuario=h.id_usuario JOIN idiomas i ON"
                        + " i.id_idioma=h.id_idioma ORDER BY h.id_historial DESC")
                .stream()
                .map(
                    row -> {
                      var copy = new HashMap<>(row);
                      copy.put("decision", Rules.academicDecision(row));
                      return copy;
                    })
                .toList()
            : List.of());
    m.addAttribute(
        "profiles",
        db.rows(
            "SELECT u.id_usuario,u.nombre_completo,u.correo,CASE WHEN EXISTS(SELECT 1 FROM"
                + " roles_permisos r WHERE r.id_usuario=u.id_usuario AND r.tipo_permiso='ADMIN')"
                + " THEN 'Administrador' WHEN EXISTS(SELECT 1 FROM roles_permisos r WHERE"
                + " r.id_usuario=u.id_usuario AND r.tipo_permiso='PROFESOR') THEN 'Profesor' ELSE"
                + " 'Usuario' END AS perfil FROM usuarios u ORDER BY u.nombre_completo"));
    m.addAttribute(
        "teachers",
        db.rows(
            "SELECT u.id_usuario,u.nombre_completo FROM usuarios u WHERE u.estado_cuenta='Activa'"
                + " AND EXISTS(SELECT 1 FROM roles_permisos r WHERE r.id_usuario=u.id_usuario AND"
                + " r.tipo_permiso='PROFESOR') AND NOT EXISTS(SELECT 1 FROM roles_permisos r WHERE"
                + " r.id_usuario=u.id_usuario AND r.tipo_permiso='ADMIN') ORDER BY"
                + " u.nombre_completo"));
    m.addAttribute(
        "assignments",
        db.rows(
            "SELECT g.id_grupo,u.nombre_completo FROM grupos g JOIN roles_permisos r ON"
                + " r.tipo_permiso=CONCAT('DOCENTE_GRUPO_',g.id_grupo) JOIN usuarios u ON"
                + " u.id_usuario=r.id_usuario WHERE EXISTS(SELECT 1 FROM roles_permisos p WHERE"
                + " p.id_usuario=u.id_usuario AND p.tipo_permiso='PROFESOR')"));
    return "admin";
  }

  @PostMapping("/convocatorias")
  @PreAuthorize("hasAuthority('ADMIN')")
  String call(
      Authentication a,
      @RequestParam String nombre,
      @RequestParam LocalDateTime inicio,
      @RequestParam LocalDateTime fin,
      @RequestParam int capacidad) {
    admin.call(portal.user(a), nombre, inicio, fin, capacidad);
    return "redirect:/admin#convocatorias";
  }

  @PostMapping("/convocatorias/{id}")
  @PreAuthorize("hasAuthority('ADMIN')")
  String configure(
      Authentication a,
      @PathVariable int id,
      @RequestParam int capacidad,
      @RequestParam(defaultValue = "false") boolean cerrar,
      @RequestParam(required = false) LocalDateTime inicio,
      @RequestParam(required = false) LocalDateTime fin) {
    admin.configure(portal.user(a), id, capacidad, cerrar, inicio, fin);
    return "redirect:/admin#convocatorias";
  }

  @PostMapping("/idiomas")
  @PreAuthorize("hasAuthority('ADMIN')")
  String language(Authentication a, @RequestParam String nombre) {
    admin.language(portal.user(a), nombre);
    return "redirect:/admin#oferta";
  }

  @PostMapping("/grupos")
  @PreAuthorize("hasAuthority('ADMIN')")
  String group(
      Authentication a,
      @RequestParam int convocatoria,
      @RequestParam int idioma,
      @RequestParam String nivel,
      @RequestParam String modalidad,
      @RequestParam String profesor,
      @RequestParam String aula,
      @RequestParam String dias,
      @RequestParam String horario,
      @RequestParam int cupo) {
    admin.group(
        portal.user(a),
        convocatoria,
        idioma,
        nivel,
        modalidad,
        profesor,
        aula,
        dias,
        horario,
        cupo);
    return "redirect:/admin#oferta";
  }

  @PostMapping("/grupos/{id}")
  @PreAuthorize("hasAuthority('ADMIN')")
  String editGroup(
      Authentication a,
      @PathVariable int id,
      @RequestParam String profesor,
      @RequestParam String aula,
      @RequestParam String dias,
      @RequestParam String horario,
      @RequestParam int cupo,
      @RequestParam(defaultValue = "false") boolean activo) {
    admin.editGroup(portal.user(a), id, profesor, aula, dias, horario, cupo, activo);
    return "redirect:/admin#oferta";
  }

  @PostMapping("/idiomas/{id}")
  @PreAuthorize("hasAuthority('ADMIN')")
  String languageState(
      Authentication a,
      @PathVariable int id,
      @RequestParam(defaultValue = "false") boolean activo) {
    admin.languageState(portal.user(a), id, activo);
    return "redirect:/admin#oferta";
  }

  @PostMapping("/prioridades")
  @PreAuthorize("hasAuthority('ADMIN')")
  String priority(
      Authentication a,
      @RequestParam int usuario,
      @RequestParam(defaultValue = "false") boolean becario,
      @RequestParam int puntaje,
      @RequestParam BigDecimal promedio,
      @RequestParam(defaultValue = "false") boolean nuevo) {
    admin.priority(portal.user(a), usuario, becario, puntaje, promedio, nuevo);
    return "redirect:/admin#prioridad";
  }

  @PostMapping("/historial")
  @PreAuthorize("hasAuthority('ADMIN')")
  String history(
      Authentication a,
      @RequestParam int usuario,
      @RequestParam int idioma,
      @RequestParam String nivel,
      @RequestParam(required = false) Integer calificacion,
      @RequestParam int inactividad,
      @RequestParam(defaultValue = "false") boolean examen,
      org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
    flash.addFlashAttribute(
        "success",
        admin.history(portal.user(a), usuario, idioma, nivel, calificacion, inactividad, examen));
    return "redirect:/admin#historial";
  }

  @PostMapping("/perfiles")
  @PreAuthorize("hasAuthority('ADMIN')")
  String profile(Authentication a, @RequestParam int usuario, @RequestParam String perfil) {
    admin.profile(portal.user(a), usuario, perfil);
    return "redirect:/admin#seguridad";
  }

  @PostMapping("/solicitudes/{id}/beca")
  @PreAuthorize("hasAuthority('ADMIN')")
  String scholarship(Authentication a, @PathVariable int id, @RequestParam String estado) {
    payments.scholarship(portal.user(a), id, estado);
    return "redirect:/admin#solicitudes";
  }

  @GetMapping("/solicitudes/{id}")
  @PreAuthorize("hasAuthority('ADMIN')")
  String review(@PathVariable int id, Model m) {
    m.addAttribute(
        "request",
        db.one(
            "SELECT s.*,u.nombre_completo FROM solicitudes_inscripcion s JOIN usuarios u ON"
                + " u.id_usuario=s.id_usuario WHERE id_solicitud=?",
            id));
    m.addAttribute(
        "documents",
        DocumentAssessment.display(
            db.rows(
                "SELECT * FROM documentos_comprobantes WHERE id_solicitud=? ORDER BY id_documento"
                    + " DESC",
                id)));
    return "revision";
  }

  @PostMapping("/solicitudes/{id}/dictamen")
  @PreAuthorize("hasAuthority('ADMIN')")
  String decide(
      Authentication a,
      @PathVariable int id,
      @RequestParam String pago,
      @RequestParam String identificacion,
      @RequestParam(defaultValue = "") String observacionPago,
      @RequestParam(defaultValue = "") String observacionIdentificacion)
      throws Exception {
    payments.reviewDocuments(
        portal.user(a),
        id,
        new DocumentAssessment(pago, observacionPago, identificacion, observacionIdentificacion));
    return "redirect:/admin/solicitudes/" + id;
  }

  @GetMapping("/usuarios/{id}")
  @PreAuthorize("hasAuthority('ADMIN')")
  String studentDetail(@PathVariable int id, Model m) {
    m.addAttribute(
        "student",
        db.one(
            "SELECT id_usuario,nombre_completo,curp,telefono,correo,tipo_usuario,estado_cuenta FROM"
                + " usuarios WHERE id_usuario=?",
            id));
    m.addAttribute(
        "histories",
        db
            .rows(
                "SELECT h.*,i.nombre AS idioma FROM historial_academico h JOIN idiomas i ON"
                    + " i.id_idioma=h.id_idioma WHERE h.id_usuario=? ORDER BY h.fecha_registro"
                    + " DESC,h.id_historial DESC",
                id)
            .stream()
            .map(
                row -> {
                  var copy = new HashMap<>(row);
                  copy.put("decision", Rules.academicDecision(row));
                  return copy;
                })
            .toList());
    m.addAttribute(
        "priorities", db.rows("SELECT * FROM prioridades_usuario WHERE id_usuario=?", id));
    return "expediente";
  }

  @PostMapping("/becas")
  @PreAuthorize("hasAuthority('ADMIN')")
  String preaward(
      Authentication a,
      @RequestParam int usuario,
      @RequestParam int convocatoria,
      @RequestParam String estado,
      @RequestParam(defaultValue = "") String observaciones) {
    scholarships.record(portal.user(a), usuario, convocatoria, estado, observaciones);
    return "redirect:/admin#usuarios";
  }

  @PostMapping("/grupos/{id}/profesor")
  @PreAuthorize("hasAuthority('ADMIN')")
  String teacher(
      Authentication a, @PathVariable int id, @RequestParam(required = false) Integer profesor) {
    teacherService.assign(portal.user(a), id, profesor);
    return "redirect:/admin#oferta";
  }

  private List<Map<String, Object>> reportRequests() {
    return db.rows(
        "SELECT estado_inscripcion AS estado,COUNT(*) AS total FROM solicitudes_inscripcion GROUP"
            + " BY estado_inscripcion ORDER BY estado_inscripcion");
  }

  private List<Map<String, Object>> reportGroups() {
    return db.rows(
        "SELECT i.nombre AS idioma,g.nivel,g.cupo_total,(g.cupo_total-g.cupo_disponible) AS"
            + " ocupados FROM grupos g JOIN idiomas i ON i.id_idioma=g.id_idioma ORDER BY"
            + " i.nombre,g.nivel,g.id_grupo");
  }

  @PostMapping("/respaldos")
  @PreAuthorize("hasAuthority('ADMIN')")
  String createBackup(Authentication a) throws Exception {
    var path = backups.create();
    db.audit(portal.user(a), "Respaldo manual", path.getFileName().toString());
    return "redirect:/admin#respaldos";
  }

  @GetMapping("/respaldos/{name}")
  @PreAuthorize("hasAuthority('ADMIN')")
  org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> downloadBackup(
      @PathVariable String name) {
    var path = backups.file(name);
    return org.springframework.http.ResponseEntity.ok()
        .contentType(org.springframework.http.MediaType.parseMediaType("application/zip"))
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .header("Content-Disposition", "attachment; filename=" + name)
        .body(new org.springframework.core.io.FileSystemResource(path));
  }

  @GetMapping("/reportes.csv")
  @PreAuthorize("hasAuthority('ADMIN')")
  org.springframework.http.ResponseEntity<String> reports() {
    StringBuilder csv = new StringBuilder("\uFEFFEstado;Solicitudes\r\n");
    for (var row : reportRequests())
      csv.append(cell(row.get("estado"))).append(';').append(row.get("total")).append("\r\n");
    csv.append("\r\nIdioma;Nivel;Ocupados;Cupo total\r\n");
    for (var row : reportGroups())
      csv.append(cell(row.get("idioma")))
          .append(';')
          .append(cell(row.get("nivel")))
          .append(';')
          .append(row.get("ocupados"))
          .append(';')
          .append(row.get("cupo_total"))
          .append("\r\n");
    return org.springframework.http.ResponseEntity.ok()
        .contentType(org.springframework.http.MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .header("Content-Disposition", "attachment; filename=reporte-celex.csv")
        .body(csv.toString());
  }

  private static String cell(Object value) {
    String text = Objects.toString(value, "");
    if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) text = "'" + text;
    return "\"" + text.replace("\"", "\"\"") + "\"";
  }
}
