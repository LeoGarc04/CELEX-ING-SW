package mx.ipn.esime.celex.web;

import java.util.Objects;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@ControllerAdvice
public class PageContext {
  private final mx.ipn.esime.celex.repository.Db db;
  private final String ipnFee, publicFee;

  @org.springframework.beans.factory.annotation.Autowired
  private mx.ipn.esime.celex.service.CallSettings settings;

  @org.springframework.beans.factory.annotation.Autowired private java.time.Clock portalClock;

  @org.springframework.beans.factory.annotation.Value("${celex.public-testing:false}")
  private boolean publicTesting;

  public PageContext(
      mx.ipn.esime.celex.repository.Db db,
      @org.springframework.beans.factory.annotation.Value("${celex.fee-ipn}") String ipnFee,
      @org.springframework.beans.factory.annotation.Value("${celex.fee-public}") String publicFee) {
    this.ipnFee = ipnFee;
    this.publicFee = publicFee;
    this.db = db;
  }

  @ModelAttribute
  void navigation(
      Authentication a,
      org.springframework.ui.Model m,
      jakarta.servlet.http.HttpServletRequest request) {
    m.addAttribute("queueFocused", Boolean.TRUE.equals(request.getAttribute("queue.focused")));
    m.addAttribute(
        "focusedQueueLink", Objects.toString(request.getAttribute("queue.path"), "/panel"));
    m.addAttribute("publicTesting", publicTesting);
    var contact = new java.util.HashMap<String, Object>(settings.read("Contacto CELEX"));
    contact.putIfAbsent("telefono", "5537871513");
    contact.putIfAbsent("correo", "celex0511@gmail.com");
    m.addAttribute(
        "contactConfigured",
        !contact.get("telefono").toString().isBlank()
            || !contact.get("correo").toString().isBlank());
    m.addAttribute("contact", contact);
    m.addAttribute(
        "teacher",
        a != null
            && a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("PROFESOR")));
    for (var entry :
        java.util.Map.of(
                "manager",
                "GESTION_PERMISOS",
                "config",
                "CONFIGURACION",
                "academic",
                "ACADEMICO",
                "financial",
                "FINANCIERO",
                "auditor",
                "AUDITORIA")
            .entrySet())
      m.addAttribute(
          entry.getKey(),
          a != null
              && a.getAuthorities().stream()
                  .anyMatch(permission -> permission.getAuthority().equals(entry.getValue())));
    m.addAttribute("englishLevels", mx.ipn.esime.celex.service.EnglishLevels.ALL);
    m.addAttribute("ipnFee", ipnFee);
    m.addAttribute("publicFee", publicFee);
    String path = request.getRequestURI();
    String section =
        path.startsWith("/tramites")
            ? "tramites"
            : path.startsWith("/perfil")
                ? "perfil"
                : path.equals("/calendario")
                    ? "calendario"
                    : path.equals("/mapa-curricular")
                        ? "mapa"
                        : path.equals("/pagos")
                            ? "pagos"
                            : path.startsWith("/profesor")
                                ? "profesor"
                                : path.startsWith("/fila/") && path.endsWith("/grupos")
                                    ? "oferta"
                                    : path.startsWith("/fila/")
                                        ? "fila"
                                        : path.startsWith("/solicitud/")
                                            ? "documentos"
                                            : path.equals("/panel") ? "inicio" : "";
    m.addAttribute("activeSection", section);
    m.addAttribute("pagePath", request.getRequestURI());
    m.addAttribute("adminArea", request.getRequestURI().startsWith("/admin"));
    m.addAttribute("displayName", "Visitante");
    String queue = "/panel", offers = "/panel", application = "/panel", receipt = "/panel";
    if (a != null) {
      var users =
          db.rows("SELECT id_usuario,nombre_completo FROM usuarios WHERE correo=?", a.getName());
      if (!users.isEmpty()) {
        var user = users.get(0);
        m.addAttribute("displayName", user.get("nombre_completo"));
        var turns =
            db.rows(
                "SELECT f.folio,f.estado_turno FROM fila_virtual f JOIN convocatorias c ON"
                    + " c.id_convocatoria=f.id_convocatoria WHERE f.id_usuario=? AND f.estado_turno"
                    + " IN ('En espera','Atendiendo') AND c.estado NOT IN ('Cerrada','Borrador')"
                    + " AND c.fecha_fin_fila>? ORDER BY f.id_turno DESC LIMIT 1",
                user.get("id_usuario"),
                java.time.LocalDateTime.now(portalClock));
        if (!turns.isEmpty()) {
          var turn = turns.get(0);
          queue = "/fila/" + turn.get("folio");
          if ("Atendiendo".equals(turn.get("estado_turno"))) offers = queue + "/grupos";
        }
        var requests =
            db.rows(
                "SELECT id_solicitud,estado_inscripcion FROM solicitudes_inscripcion WHERE"
                    + " id_usuario=? ORDER BY id_solicitud DESC LIMIT 1",
                user.get("id_usuario"));
        if (!requests.isEmpty()) {
          var current = requests.get(0);
          application = "/solicitud/" + current.get("id_solicitud");
          receipt =
              "Confirmada".equals(current.get("estado_inscripcion"))
                  ? application + "/comprobante"
                  : application;
        }
      }
    }
    m.addAttribute("queueLink", queue);
    m.addAttribute("offersLink", offers);
    m.addAttribute("requestLink", application);
    m.addAttribute("receiptLink", receipt);
  }

  @ModelAttribute("staff")
  boolean staff(Authentication a) {
    return a != null
        && a.getAuthorities().stream().anyMatch(x -> Set.of("ADMIN").contains(x.getAuthority()));
  }

  @ModelAttribute("signedIn")
  boolean signedIn(Authentication a) {
    return a != null;
  }
}
