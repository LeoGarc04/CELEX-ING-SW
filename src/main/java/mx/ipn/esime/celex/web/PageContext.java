package mx.ipn.esime.celex.web;

import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@ControllerAdvice
public class PageContext {
  private final mx.ipn.esime.celex.repository.Db db;
  private final String ipnFee, publicFee;

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
    m.addAttribute("publicTesting", publicTesting);
    m.addAttribute(
        "teacher",
        a != null
            && a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("PROFESOR")));
    for (var entry :
        java.util.Map.of(
                "manager",
                "ADMIN",
                "config",
                "ADMIN",
                "academic",
                "ADMIN",
                "financial",
                "ADMIN",
                "auditor",
                "ADMIN")
            .entrySet())
      m.addAttribute(
          entry.getKey(),
          a != null
              && a.getAuthorities().stream()
                  .anyMatch(
                      permission ->
                          permission.getAuthority().equals("ADMIN")
                              || permission.getAuthority().equals(entry.getValue())));
    m.addAttribute("englishLevels", mx.ipn.esime.celex.service.EnglishLevels.ALL);
    m.addAttribute("ipnFee", ipnFee);
    m.addAttribute("publicFee", publicFee);
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
                "SELECT folio,estado_turno FROM fila_virtual WHERE id_usuario=? ORDER BY id_turno"
                    + " DESC LIMIT 1",
                user.get("id_usuario"));
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
