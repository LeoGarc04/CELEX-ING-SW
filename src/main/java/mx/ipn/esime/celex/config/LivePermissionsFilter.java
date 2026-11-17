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
      var updated =
          UsernamePasswordAuthenticationToken.authenticated(auth.getPrincipal(), null, permissions);
      updated.setDetails(auth.getDetails());
      SecurityContextHolder.getContext().setAuthentication(updated);
    }
    chain.doFilter(req, res);
  }
}
