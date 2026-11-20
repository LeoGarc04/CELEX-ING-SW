package mx.ipn.esime.celex.web;

import jakarta.validation.constraints.*;

public record Registration(
    @NotBlank(message = "Escribe tus apellidos y nombre(s)")
        @Size(max = 150, message = "El nombre admite hasta 150 caracteres")
        String nombre,
    String curp,
    @NotBlank(message = "Escribe tu teléfono")
        @Pattern(regexp = "[+0-9 ()-]{7,15}", message = "Teléfono inválido")
        String telefono,
    @NotBlank(message = "Escribe tu correo electrónico")
        @Email(message = "Escribe un correo electrónico válido")
        @Size(max = 100, message = "El correo admite hasta 100 caracteres")
        String correo,
    @NotBlank(message = "Escribe una contraseña")
        @Size(min = 8, max = 64, message = "La contraseña debe tener entre 8 y 64 caracteres")
        @Pattern(
            regexp = "(?s)(?=.*\\p{Lu})(?=.*\\p{Nd}).*",
            message = "La contraseña debe incluir una mayúscula y un número")
        String password,
    @NotBlank(message = "Selecciona alumno del IPN o público en general")
        @Pattern(regexp = "IPN|EXTERNO", message = "Tipo de registro inválido")
        String tipoRegistro,
    String boleta, java.time.LocalDate fechaNacimiento) {
  public Registration {
    curp = curp == null ? "" : curp.trim().toUpperCase(java.util.Locale.ROOT);
    boleta = boleta == null ? "" : boleta.trim();
    tipoRegistro = tipoRegistro == null ? "" : tipoRegistro;
  }

  public Registration(String nombre, String curp, String telefono, String correo, String password) {
    this(nombre, curp, telefono, correo, password, "EXTERNO", "");
  }

  public Registration(String nombre, String curp, String telefono, String correo, String password,
      String tipoRegistro, String boleta) {
    this(nombre, curp, telefono, correo, password, tipoRegistro, boleta, null);
  }

  public Registration(String nombre, String curp, String telefono, String correo, String password,
      java.time.LocalDate fechaNacimiento) {
    this(nombre, curp, telefono, correo, password, "EXTERNO", "", fechaNacimiento);
  }

  public boolean ipn() {
    return "IPN".equals(tipoRegistro);
  }

  @AssertTrue(
      message =
          "Para alumnos IPN, ingresa una boleta de 10 dígitos. Para público general, una CURP"
              + " válida de 18 caracteres. Utiliza solo el identificador correspondiente.")
  public boolean isDocumentoValido() {
    return ipn()
        ? boleta.matches("[0-9]{10}") && curp.isBlank()
        : "EXTERNO".equals(tipoRegistro)
            && curp.matches("[A-Z][AEIOUX][A-Z]{2}[0-9]{6}[HM][A-Z]{5}[A-Z0-9][0-9]")
            && boleta.isBlank();
  }
}
