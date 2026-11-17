package mx.ipn.esime.celex.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/** FIFO de confirmaciones según su recepción en esta instancia del servidor. */
public final class OrderedEnrollmentFilter
    extends org.springframework.web.filter.OncePerRequestFilter {
  private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
  private int waiting;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!request.getMethod().equals("POST")
        || !request.getServletPath().matches("/fila/[^/]+/grupo")) {
      chain.doFilter(request, response);
      return;
    }
    CompletableFuture<Void> previous;
    var current = new CompletableFuture<Void>();
    synchronized (this) {
      if (waiting >= 1000) {
        response.setStatus(429);
        response.setHeader("Retry-After", "2");
        return;
      }
      waiting++;
      previous = tail;
      tail = current;
    }
    previous.join();
    try {
      chain.doFilter(request, response);
    } finally {
      synchronized (this) {
        waiting--;
      }
      current.complete(null);
    }
  }
}
