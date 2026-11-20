package mx.ipn.esime.celex.service;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import mx.ipn.esime.celex.repository.Db;
import mx.ipn.esime.celex.web.Registration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
  private final mx.ipn.esime.celex.repository.UsuarioRepository users;
  private final Db db;
  private final PasswordEncoder encoder;
  private final MailService mail;
  private final Clock clock;
  private final Set<String> domains;
  @org.springframework.beans.factory.annotation.Autowired private AgePolicy ages;
  private final SecureRandom random = new SecureRandom();

  public AccountService(
      Db db,
      PasswordEncoder encoder,
      MailService mail,
      Clock clock,
      @Value("${celex.institutional-domains}") String domains,
      mx.ipn.esime.celex.repository.UsuarioRepository users) {
    this.users = users;
    this.db = db;
    this.encoder = encoder;
    this.mail = mail;
    this.clock = clock;
    this.domains =
        new HashSet<>(
            Arrays.stream(domains.toLowerCase(Locale.ROOT).split(","))
                .map(String::trim)
                .filter(x -> !x.isBlank())
                .toList());
  }

  public static String normalize(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }

  public String classify(String email) {
    if (domains.isEmpty())
      throw new IllegalArgumentException(
          "CELEX aún no ha configurado los dominios institucionales autorizados.");
    String normalized = normalize(email);
    String domain = normalized.substring(normalized.lastIndexOf('@') + 1);
    return domains.contains(domain) ? "Comunidad IPN" : "Público general";
  }

  public static void validatePassword(String value) {
    if (value == null
        || value.codePointCount(0, value.length()) < 8
        || value.length() > 64
        || !value.matches("(?s)(?=.*\\p{Lu})(?=.*\\p{Nd}).*")
        || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw new IllegalArgumentException(
          "Contraseña: usa de 8 a 64 caracteres, al menos una mayúscula y un número (máximo 72"
              + " bytes UTF-8).");
  }

  @Transactional
  public void register(Registration r) {
    validatePassword(r.password());
    ages.validate(r.fechaNacimiento());
    if (!r.isDocumentoValido())
      throw new IllegalArgumentException(
          "Selecciona tu tipo de registro e ingresa una boleta de 10 dígitos para IPN o una CURP"
              + " válida para público general.");
    boolean institutional = "Comunidad IPN".equals(classify(r.correo()));
    if (r.ipn() != institutional)
      throw new IllegalArgumentException(
          r.ipn()
              ? "Los alumnos del IPN deben usar su correo @alumno.ipn.mx."
              : "Si usas correo institucional, selecciona alumno del IPN e ingresa tu boleta.");
    String curp = r.ipn() ? null : r.curp(), boleta = r.ipn() ? r.boleta() : null;
    if (!db.rows(
            "SELECT id_usuario FROM usuarios WHERE correo=? OR curp=? OR boleta=?",
            normalize(r.correo()),
            curp,
            boleta)
        .isEmpty())
      throw new IllegalArgumentException("El correo, la CURP o la boleta ya están registrados.");
    var account = new mx.ipn.esime.celex.entity.usuario();
    account.setNombreCompleto(r.nombre().trim().replaceAll("\\s+", " "));
    account.setCurp(curp);
    account.setBoleta(boleta);
    account.setTelefono(r.telefono());
    account.setCorreo(normalize(r.correo()));
    account.setPasswordHash(encoder.encode(r.password()));
    account.setTipoUsuario("Público general");
    account.setEstadoCuenta("Inactiva");
    account.setIntentosFallidos(0);
    int id = users.saveAndFlush(account).getIdUsuario();
    ages.record(id, r.fechaNacimiento());
    issue(id, normalize(r.correo()), "Activacion");
    db.audit(id, "Registro de cuenta", "Cuenta pendiente de verificación");
  }

  private void issue(int id, String email, String type) {
    db.update(
        "UPDATE verificaciones_codigo SET usado=TRUE WHERE id_usuario=? AND tipo_codigo=?",
        id,
        type);
    String alphabet = "0123456789";
    StringBuilder code = new StringBuilder();
    for (int i = 0; i < 6; i++) code.append(alphabet.charAt(random.nextInt(alphabet.length())));
    db.update(
        "INSERT INTO verificaciones_codigo(id_usuario,codigo,tipo_codigo,expira_en,usado)"
            + " VALUES(?,?,?,?,FALSE)",
        id,
        code.toString(),
        type,
        LocalDateTime.now(clock).plusMinutes(15));
    mail.send(
        email,
        "CELEX · Código de " + type,
        "Código de " + type + ":\n\n" + code + "\n\nVigencia: 15 minutos.\nNo compartas este código.");
  }

  @Transactional
  public void requestActivation(String email) {
    var found =
        db.rows(
            "SELECT * FROM usuarios WHERE correo=? AND estado_cuenta='Inactiva' FOR UPDATE",
            normalize(email));
    if (found.isEmpty()) return;
    var u = found.get(0);
    if (db.rows(
            "SELECT id_codigo FROM verificaciones_codigo WHERE id_usuario=? AND"
                + " tipo_codigo='Activacion' AND expira_en>?",
            u.get("id_usuario"),
            LocalDateTime.now(clock).plusMinutes(14))
        .isEmpty()) issue(Db.number(u, "id_usuario"), normalize(email), "Activacion");
  }

  @Transactional
  public void requestReset(String email) {
    var users = db.rows("SELECT * FROM usuarios WHERE correo=? FOR UPDATE", normalize(email));
    if (users.isEmpty()) return;
    var u = users.get(0);
    if (!"Activa".equals(u.get("estado_cuenta"))) return;
    var recent =
        db.rows(
            "SELECT id_codigo FROM verificaciones_codigo WHERE id_usuario=? AND"
                + " tipo_codigo='Recuperacion' AND expira_en>?",
            u.get("id_usuario"),
            LocalDateTime.now(clock).plusMinutes(14));
    if (recent.isEmpty()) issue(Db.number(u, "id_usuario"), normalize(email), "Recuperacion");
  }

  @Transactional
  public void verify(String email, String code, String newPassword) {
    var u = db.one("SELECT * FROM usuarios WHERE correo=? FOR UPDATE", normalize(email));
    int id = Db.number(u, "id_usuario");
    String type = newPassword == null ? "Activacion" : "Recuperacion";
    var codes =
        db.rows(
            "SELECT * FROM verificaciones_codigo WHERE id_usuario=? AND codigo=? AND tipo_codigo=?"
                + " AND usado=FALSE AND expira_en>? FOR UPDATE",
            id,
            code,
            type,
            LocalDateTime.now(clock));
    if (codes.isEmpty())
      throw new IllegalArgumentException("Código incorrecto, utilizado o caducado.");
    if (newPassword == null) {
      db.update(
          "UPDATE usuarios SET estado_cuenta='Activa',tipo_usuario=? WHERE id_usuario=?",
          classify(email),
          id);
    } else {
      validatePassword(newPassword);
      db.update(
          "UPDATE usuarios SET password_hash=?,intentos_fallidos=0,bloqueado_hasta=NULL WHERE"
              + " id_usuario=?",
          encoder.encode(newPassword),
          id);
    }
    db.update(
        "UPDATE verificaciones_codigo SET usado=TRUE WHERE id_usuario=? AND tipo_codigo=?",
        id,
        type);
    db.audit(id, type, "Código consumido");
  }
}
