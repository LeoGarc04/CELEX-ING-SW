package mx.ipn.esime.celex.config;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import mx.ipn.esime.celex.repository.Db;
import mx.ipn.esime.celex.service.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DatabaseAuthenticationProvider implements AuthenticationProvider {
  private final Db db;
  private final PasswordEncoder encoder;
  private final Clock clock;
  private final String dummy;

  public DatabaseAuthenticationProvider(Db db, PasswordEncoder encoder, Clock clock) {
    this.db = db;
    this.encoder = encoder;
    this.clock = clock;
    dummy = encoder.encode(UUID.randomUUID().toString());
  }

  @Override
  @Transactional(noRollbackFor = AuthenticationException.class)
  public Authentication authenticate(Authentication auth) {
    String email = AccountService.normalize(auth.getName());
    String password = Objects.toString(auth.getCredentials(), "");
    var users = db.rows("SELECT * FROM usuarios WHERE correo=? FOR UPDATE", email);
    if (users.isEmpty()) {
      encoder.matches(password, dummy);
      throw new BadCredentialsException("Credenciales inválidas");
    }
    var u = users.get(0);
    int id = Db.number(u, "id_usuario");
    var now = LocalDateTime.now(clock);
    Object until = u.get("bloqueado_hasta");
    LocalDateTime deadline =
        until instanceof Timestamp t ? t.toLocalDateTime() : (LocalDateTime) until;
    if (deadline != null && deadline.isAfter(now))
      throw new LockedException("Acceso bloqueado temporalmente");
    if (!encoder.matches(password, (String) u.get("password_hash"))) {
      int attempts = deadline != null ? 1 : Db.number(u, "intentos_fallidos") + 1;
      db.update(
          "UPDATE usuarios SET intentos_fallidos=?,bloqueado_hasta=? WHERE id_usuario=?",
          attempts,
          attempts >= 5 ? now.plusMinutes(10) : null,
          id);
      throw new BadCredentialsException("Credenciales inválidas");
    }
    if (!"Activa".equals(u.get("estado_cuenta"))) throw new DisabledException("Verifica tu cuenta");
    db.update(
        "UPDATE usuarios SET intentos_fallidos=0,bloqueado_hasta=NULL WHERE id_usuario=?", id);
    var authorities = Profiles.authorities(db, id);
    db.audit(id, "Inicio de sesión", "Acceso correcto");
    var result = UsernamePasswordAuthenticationToken.authenticated(email, null, authorities);
    result.setDetails(passwordVersion((String) u.get("password_hash")));
    return result;
  }

  public static String passwordVersion(String hash) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(hash.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public boolean supports(Class<?> type) {
    return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type);
  }
}
