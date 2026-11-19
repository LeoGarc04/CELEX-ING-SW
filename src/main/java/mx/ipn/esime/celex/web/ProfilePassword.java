package mx.ipn.esime.celex.web;

import jakarta.validation.constraints.*;

public record ProfilePassword(
    @NotBlank(message = "Escribe tu contraseña actual")
        @Size(max = 128, message = "Contraseña actual inválida")
        String actual,
    @NotBlank(message = "Escribe tu nueva contraseña")
        @Size(min = 8, max = 64, message = "La nueva contraseña debe tener entre 8 y 64 caracteres")
        @Pattern(
            regexp = "(?s)(?=.*\\p{Lu})(?=.*\\p{Nd}).*",
            message = "La nueva contraseña debe incluir una mayúscula y un número")
        String nueva,
    @NotBlank(message = "Confirma tu nueva contraseña")
        @Size(max = 64, message = "La confirmación admite hasta 64 caracteres")
        String confirmar) {
  @AssertTrue(message = "Las contraseñas nuevas deben coincidir")
  public boolean isConfirmada() {
    return nueva != null && nueva.equals(confirmar);
  }
}
