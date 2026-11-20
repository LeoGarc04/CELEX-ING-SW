package mx.ipn.esime.celex.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import mx.ipn.esime.celex.repository.Db;
import org.springframework.security.authentication.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class LivePermissionsFilter extends OncePerRequestFilter {
  private final Db db;
  private final java.time.Clock clock;

  public LivePermissionsFilter(Db db, java.time.Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
      var rows =
          db.rows(
              "SELECT id_usuario,password_hash FROM usuarios WHERE correo=? AND"
                  + " estado_cuenta='Activa'",
              auth.getName());
      if (rows.isEmpty()
          || (auth.getDetails() instanceof String version
              && !version.equals(
                  DatabaseAuthenticationProvider.passwordVersion(
                      (String) rows.get(0).get("password_hash"))))) {
        SecurityContextHolder.clearContext();
        req.getSession().invalidate();
        res.sendRedirect("/login");
        return;
      }
      var session = req.getSession(false);
      Object folio = session == null ? null : session.getAttribute("queue.folio");
      if (folio != null) {
        var turns =
            db.rows(
                "SELECT estado_turno,reserva_expira_en FROM fila_virtual WHERE folio=? AND"
                    + " id_usuario=?",
                folio,
                rows.get(0).get("id_usuario"));
        if (!turns.isEmpty()) {
          var turn = turns.get(0);
          boolean expired =
              "Expirado".equals(turn.get("estado_turno"))
                  || (java.util.List.of("En espera", "Atendiendo")
                          .contains(turn.get("estado_turno"))
                      && turn.get("reserva_expira_en") != null
                      && !mx.ipn.esime.celex.service.QueueService.time(
                              turn.get("reserva_expira_en"))
                          .isAfter(java.time.LocalDateTime.now(clock)));
          if (expired) {
            SecurityContextHolder.clearContext();
            session.invalidate();
            res.sendRedirect("/login?expired");
            return;
          }
        }
      }
      var permissions =
          mx.ipn.esime.celex.service.Profiles.authorities(db, Db.number(rows.get(0), "id_usuario"));
      if (permissions.stream().anyMatch(x -> x.getAuthority().equals("USUARIO"))) {
        var active =
            db.rows(
                "SELECT f.folio,f.estado_turno,f.posicion_actual FROM fila_virtual f JOIN"
                    + " convocatorias c ON c.id_convocatoria=f.id_convocatoria WHERE f.id_usuario=?"
                    + " AND f.estado_turno IN ('En espera','Atendiendo') AND f.reserva_expira_en>?"
                    + " AND c.estado<>'Cerrada' AND c.fecha_inicio_fila<=? AND c.fecha_fin_fila>?"
                    + " ORDER BY CASE WHEN f.folio=? THEN 0 ELSE 1 END,f.fecha_ingreso,f.id_turno"
                    + " LIMIT 1",
                rows.get(0).get("id_usuario"),
                java.time.LocalDateTime.now(clock),
                java.time.LocalDateTime.now(clock),
                java.time.LocalDateTime.now(clock),
                Objects.toString(folio, ""));
        if (!active.isEmpty()) {
          var turn = active.get(0);
          String queuePath = "/fila/" + turn.get("folio");
          String path = req.getServletPath();
          if (path.isEmpty()) path = req.getRequestURI().substring(req.getContextPath().length());
          boolean read = req.getMethod().equals("GET") || req.getMethod().equals("HEAD");
          boolean ready =
              "Atendiendo".equals(turn.get("estado_turno"))
                  || (Db.number(turn, "posicion_actual") >= 1 && Db.number(turn, "posicion_actual") <= mx.ipn.esime.celex.service.QueueService.ACTIVE_SLOTS);
          req.setAttribute("queue.focused", true);
          req.setAttribute("queue.path", queuePath);
          boolean allowed =
              path.startsWith("/css/")
                  || path.startsWith("/js/")
                  || Set.of("/salud", "/error", "/logout").contains(path)
                  || (((read && path.equals("/perfil"))
                          || (req.getMethod().equals("POST") && path.equals("/perfil/edad")))
                      && db.rows("SELECT id_bitacora FROM bitacora_acciones WHERE accion=? LIMIT 1",
                              "Fecha nacimiento · " + rows.get(0).get("id_usuario")).isEmpty())
                  || (read && (path.equals(queuePath) || path.equals(queuePath + "/oferta")))
                  || (req.getMethod().equals("POST") && path.equals(queuePath + "/latido"))
                  || (ready
                      && ((read && path.equals(queuePath + "/grupos"))
                          || (req.getMethod().equals("POST")
                              && (path.equals(queuePath + "/grupo")
                                  || path.equals(queuePath + "/preferencia")))));
          if (!allowed) {
            res.setStatus(303);
            res.setHeader("Location", req.getContextPath() + queuePath);
            res.setHeader("Cache-Control", "no-store");
            return;
          }
        }
      }
      var updated =
          UsernamePasswordAuthenticationToken.authenticated(auth.getPrincipal(), null, permissions);
      updated.setDetails(auth.getDetails());
      SecurityContextHolder.getContext().setAuthentication(updated);
    }
    chain.doFilter(req, res);
  }
}
