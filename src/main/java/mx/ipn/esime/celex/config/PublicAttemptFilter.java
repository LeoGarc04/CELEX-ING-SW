package mx.ipn.esime.celex.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;

public final class PublicAttemptFilter extends org.springframework.web.filter.OncePerRequestFilter {
  private record Window(long start, int count) {}

  private final Map<String, Window> counters = new HashMap<>();

  private synchronized boolean allow(String key) {
    long now = System.currentTimeMillis();
    counters.entrySet().removeIf(e -> now - e.getValue().start() > 600000);
    var old = counters.get(key);
    if (old == null) {
      if (counters.size() >= 10000) return false;
      counters.put(key, new Window(now, 1));
      return true;
    }
    if (old.count() >= 30) return false;
    counters.put(key, new Window(old.start(), old.count() + 1));
    return true;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest r, HttpServletResponse s, FilterChain c)
      throws ServletException, IOException {
    if (r.getMethod().equals("POST")
        && Set.of("/login", "/registro", "/verificar", "/reenviar", "/recuperar", "/restablecer")
            .contains(r.getServletPath())
        && !allow(
            r.getRemoteAddr()
                + "|"
                + r.getServletPath()
                + "|"
                + (r.getServletPath().equals("/login")
                    ? Objects.toString(r.getParameter("username"), "")
                        .toLowerCase(java.util.Locale.ROOT)
                    : ""))) {
      s.setStatus(429);
      s.setHeader("Retry-After", "600");
      s.setContentType("text/plain;charset=UTF-8");
      s.getWriter().write("Demasiados intentos. Espera diez minutos antes de volver a intentar.");
      return;
    }
    c.doFilter(r, s);
  }
}
