package mx.ipn.esime.celex.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "usuarios")
public class usuario {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id_usuario")
  private Integer idUsuario;

  @Column(name = "nombre_completo", nullable = false, length = 150)
  private String nombreCompleto;

  @Column(name = "curp", nullable = false, unique = true, length = 18)
  private String curp;

  @Column(name = "telefono", nullable = false, length = 15)
  private String telefono;

  @Column(name = "correo", nullable = false, unique = true, length = 100)
  private String correo;

  @Column(name = "password_hash", nullable = false, length = 255)
  private String passwordHash;

  @Column(name = "tipo_usuario", nullable = false, length = 20)
  private String tipoUsuario;

  @Column(name = "estado_cuenta", length = 20)
  private String estadoCuenta;

  @Column(name = "intentos_fallidos")
  private Integer intentosFallidos;

  @Column(name = "bloqueado_hasta")
  private LocalDateTime bloqueadoHasta;

  @Column(name = "creado_en", insertable = false, updatable = false)
  private LocalDateTime creadoEn;

  public Integer getIdUsuario() {
    return idUsuario;
  }

  public void setIdUsuario(Integer idUsuario) {
    this.idUsuario = idUsuario;
  }

  public String getNombreCompleto() {
    return nombreCompleto;
  }

  public void setNombreCompleto(String nombreCompleto) {
    this.nombreCompleto = nombreCompleto;
  }

  public String getCurp() {
    return curp;
  }

  public void setCurp(String curp) {
    this.curp = curp;
  }

  public String getTelefono() {
    return telefono;
  }

  public void setTelefono(String telefono) {
    this.telefono = telefono;
  }

  public String getCorreo() {
    return correo;
  }

  public void setCorreo(String correo) {
    this.correo = correo;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  public String getTipoUsuario() {
    return tipoUsuario;
  }

  public void setTipoUsuario(String tipoUsuario) {
    this.tipoUsuario = tipoUsuario;
  }

  public String getEstadoCuenta() {
    return estadoCuenta;
  }

  public void setEstadoCuenta(String estadoCuenta) {
    this.estadoCuenta = estadoCuenta;
  }

  public Integer getIntentosFallidos() {
    return intentosFallidos;
  }

  public void setIntentosFallidos(Integer intentosFallidos) {
    this.intentosFallidos = intentosFallidos;
  }

  public LocalDateTime getBloqueadoHasta() {
    return bloqueadoHasta;
  }

  public void setBloqueadoHasta(LocalDateTime bloqueadoHasta) {
    this.bloqueadoHasta = bloqueadoHasta;
  }

  public LocalDateTime getCreadoEn() {
    return creadoEn;
  }
}
