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

  @org.springframework.beans.factory.annotation.Autowired
  private mx.ipn.esime.celex.service.CallManagement callManagement;

  @org.springframework.beans.factory.annotation.Autowired
  private mx.ipn.esime.celex.service.CallSettings settings;

  @org.springframework.beans.factory.annotation.Autowired
  private mx.ipn.esime.celex.service.IntakeService intake;

  @org.springframework.beans.factory.annotation.Autowired private java.time.Clock intakeClock;

  private boolean allowed(Authentication a, String p) {
    return a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals(p));
  }

  @GetMapping
  String admin(Authentication a, Model m) throws java.io.IOException {
    m.addAttribute("config", allowed(a, "CONFIGURACION"));
    m.addAttribute("academic", allowed(a, "ACADEMICO"));
    m.addAttribute("financial", allowed(a, "FINANCIERO"));
    m.addAttribute("auditor", allowed(a, "AUDITORIA"));
    m.addAttribute("manager", allowed(a, "GESTION_PERMISOS"));
    m.addAttribute(
        "calls",
        (allowed(a, "CONFIGURACION") || allowed(a, "ACADEMICO") || allowed(a, "FINANCIERO"))
            ? db.rows("SELECT * FROM convocatorias ORDER BY id_convocatoria DESC")
            : List.of());
    m.addAttribute(
        "languages", allowed(a, "ACADEMICO") ? db.rows("SELECT * FROM idiomas") : List.of());
    m.addAttribute(
        "groups",
        (allowed(a, "ACADEMICO") || allowed(a, "CONFIGURACION"))
            ? db.rows(
                "SELECT g.*,c.nombre AS convocatoria_nombre,i.nombre AS idioma_nombre FROM grupos g"
                    + " LEFT JOIN convocatorias c ON c.id_convocatoria=g.id_convocatoria LEFT JOIN"
                    + " idiomas i ON i.id_idioma=g.id_idioma ORDER BY g.id_grupo DESC")
            : List.of());
    m.addAttribute(
        "users",
        allowed(a, "ACADEMICO") || allowed(a, "FINANCIERO") || allowed(a, "GESTION_PERMISOS")
            ? db.rows(
                "SELECT id_usuario,nombre_completo,correo,tipo_usuario FROM usuarios ORDER BY"
                    + " nombre_completo,id_usuario")
            : List.of());
    m.addAttribute(
        "requests",
        allowed(a, "FINANCIERO")
            ? db.rows(
                "SELECT s.*,u.nombre_completo FROM solicitudes_inscripcion s JOIN usuarios u ON"
                    + " u.id_usuario=s.id_usuario ORDER BY"
                    + " u.nombre_completo,u.id_usuario,s.id_solicitud DESC")
            : List.of());
    m.addAttribute(
        "logs",
        allowed(a, "AUDITORIA")
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
    boolean auditor = allowed(a, "AUDITORIA");
    m.addAttribute("backupEnabled", (auditor || allowed(a, "CONFIGURACION")) && backups.enabled());
    m.addAttribute(
        "backups", (auditor || allowed(a, "CONFIGURACION")) ? backups.list() : List.of());
    m.addAttribute("reportRequests", auditor ? reportRequests() : List.of());
    m.addAttribute("reportGroups", auditor ? reportGroups() : List.of());
    m.addAttribute("scholarships", allowed(a, "FINANCIERO") ? scholarships.recent() : List.of());
    m.addAttribute(
        "histories",
        allowed(a, "ACADEMICO")
            ? db
                .rows(
                    "SELECT h.*,u.nombre_completo,i.nombre AS idioma FROM historial_academico h"
                        + " JOIN usuarios u ON u.id_usuario=h.id_usuario JOIN idiomas i ON"
                        + " i.id_idioma=h.id_idioma ORDER BY"
                        + " u.nombre_completo,u.id_usuario,h.id_historial DESC")
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
    m.addAttribute(
        "adminPermissions",
        db.rows(
            "SELECT r.id_usuario,u.nombre_completo,r.tipo_permiso FROM roles_permisos r JOIN"
                + " usuarios u ON u.id_usuario=r.id_usuario WHERE r.tipo_permiso IN"
                + " ('CONFIGURACION','ACADEMICO','FINANCIERO','AUDITORIA','PERMISOS_LIMITADOS')"
                + " ORDER BY u.nombre_completo,r.tipo_permiso"));
    if (allowed(a, "GESTION_PERMISOS")) {
      @SuppressWarnings("unchecked")
      var profileRows = (List<Map<String, Object>>) m.asMap().get("profiles");
      for (var profile : profileRows)
        profile.put(
            "permisos_csv",
            mx.ipn.esime.celex.service.Profiles.authorities(db, Db.number(profile, "id_usuario"))
                .stream()
                .map(x -> x.getAuthority())
                .filter(mx.ipn.esime.celex.service.Profiles.ADMIN_PERMISSIONS::contains)
                .sorted()
                .collect(java.util.stream.Collectors.joining(",")));
    }
    @SuppressWarnings("unchecked")
    var configuredCalls = (List<Map<String, Object>>) m.asMap().get("calls");
    for (var call : configuredCalls) {
      int id = mx.ipn.esime.celex.repository.Db.number(call, "id_convocatoria");
      call.put("inicioClases", settings.classes(id));
      call.put("cupoExamen", settings.examCapacity(id));
    }
    @SuppressWarnings("unchecked")
    var timedRequests = (List<Map<String, Object>>) m.asMap().get("requests");
    for (var request : timedRequests) {
      var sent = request.get("fecha_envio");
      var deadline =
          sent == null ? null : mx.ipn.esime.celex.service.QueueService.time(sent).plusHours(48);
      request.put("revision_limite", deadline);
      request.put(
          "revision_vencida",
          deadline != null
              && java.time.LocalDateTime.now(intakeClock).isAfter(deadline)
              && "Pendiente de revisión".equals(request.get("estado_inscripcion")));
    }
    return "admin";
  }

  @PostMapping("/convocatorias")
  @PreAuthorize("hasAuthority('CONFIGURACION')")
  String call(
      Authentication a,
      @RequestParam String nombre,
      @RequestParam LocalDateTime inicio,
      @RequestParam LocalDateTime fin,
      @RequestParam int capacidad,
      @RequestParam LocalDateTime inicioClases,
      @RequestParam int cupoExamen,
      @RequestParam(required = false) List<Integer> grupos,
      @RequestParam Map<String, String> fields) {
    var first = new HashMap<String, String>();
    for (String key :
        List.of("idioma", "nivel", "modalidad", "profesor", "aula", "dias", "horario", "cupo"))
      first.put(key, fields.getOrDefault("inicial_" + key, ""));
    callManagement.create(
        portal.user(a), nombre, inicio, fin, capacidad, inicioClases, cupoExamen, grupos, first);
    return "redirect:/admin#convocatorias";
  }

  @PostMapping("/convocatorias/{id}")
  @PreAuthorize("hasAuthority('CONFIGURACION')")
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
  @PreAuthorize("hasAuthority('ACADEMICO')")
  String language(Authentication a, @RequestParam String nombre) {
    admin.language(portal.user(a), nombre);
    return "redirect:/admin#oferta";
  }

  @PostMapping("/grupos")
  @PreAuthorize("hasAuthority('ACADEMICO')")
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
  @PreAuthorize("hasAuthority('ACADEMICO')")
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

  @PostMapping("/grupos/{id}/eliminar")
  @PreAuthorize("hasAuthority('ACADEMICO')")
  String deleteGroup(
      Authentication a,
      @PathVariable int id,
      org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
    admin.deleteGroup(portal.user(a), id);
    flash.addFlashAttribute("success", "Grupo eliminado correctamente.");
    return "redirect:/admin#oferta";
  }

  @PostMapping("/idiomas/{id}")
  @PreAuthorize("hasAuthority('ACADEMICO')")
  String languageState(
      Authentication a,
      @PathVariable int id,
      @RequestParam(defaultValue = "false") boolean activo) {
    admin.languageState(portal.user(a), id, activo);
    return "redirect:/admin#oferta";
  }

  @PostMapping("/prioridades")
  @PreAuthorize("hasAuthority('ACADEMICO')")
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
  @PreAuthorize("hasAuthority('ACADEMICO')")
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
  @PreAuthorize("hasAuthority('GESTION_PERMISOS')")
  String profile(Authentication a, @RequestParam int usuario, @RequestParam String perfil) {
    admin.profile(portal.user(a), usuario, perfil);
    return "redirect:/admin#seguridad";
  }

  @PostMapping("/permisos")
  @PreAuthorize("hasAuthority('GESTION_PERMISOS')")
  String permissions(
      Authentication a,
      @RequestParam int usuario,
      @RequestParam(required = false) java.util.List<String> permisos) {
    admin.permissions(
        portal.user(a),
        usuario,
        permisos == null ? java.util.Set.of() : new java.util.HashSet<>(permisos));
    return "redirect:/admin#seguridad";
  }

  @PostMapping("/solicitudes/{id}/beca")
  @PreAuthorize("hasAuthority('FINANCIERO')")
  String scholarship(Authentication a, @PathVariable int id, @RequestParam String estado) {
    payments.scholarship(portal.user(a), id, estado);
    return "redirect:/admin#solicitudes";
  }

  @GetMapping("/solicitudes/{id}")
  @PreAuthorize("hasAuthority('FINANCIERO')")
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
    var preference = settings.preference(id);
    m.addAttribute("newFlow", !preference.isEmpty());
    m.addAttribute("preference", preference);
    m.addAttribute("assigned", settings.assigned(id));
    m.addAttribute("assignable", intake.assignable(id));
    return "revision";
  }

  @PostMapping("/solicitudes/{id}/dictamen")
  @PreAuthorize("hasAuthority('FINANCIERO')")
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
  @PreAuthorize("hasAuthority('ACADEMICO')")
  String studentDetail(@PathVariable int id, Model m) {
    m.addAttribute(
        "student",
        db.one(
            "SELECT"
                + " id_usuario,nombre_completo,curp,boleta,telefono,correo,tipo_usuario,estado_cuenta"
                + " FROM usuarios WHERE id_usuario=?",
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
  @PreAuthorize("hasAuthority('FINANCIERO')")
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
  @PreAuthorize("hasAuthority('ACADEMICO')")
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
  @PreAuthorize("hasAuthority('CONFIGURACION')")
  String createBackup(Authentication a) throws Exception {
    var path = backups.create();
    db.audit(portal.user(a), "Respaldo manual", path.getFileName().toString());
    return "redirect:/admin#respaldos";
  }

  @GetMapping("/respaldos/{name}")
  @PreAuthorize("hasAuthority('CONFIGURACION')")
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
  @PreAuthorize("hasAuthority('AUDITORIA')")
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
