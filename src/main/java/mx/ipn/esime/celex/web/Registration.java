package mx.ipn.esime.celex.web;

import jakarta.validation.constraints.*;

public record Registration(
    @NotBlank(message = "Este campo es obligatorio")
        @Size(max = 150, message = "El nombre admite hasta 150 caracteres")
        String nombre,
    @NotBlank(message = "Este campo es obligatorio")
        @Pattern(
            regexp = "[A-Z][AEIOUX][A-Z]{2}[0-9]{6}[HM][A-Z]{5}[A-Z0-9][0-9]",
            message = "CURP inválida: usa 18 caracteres en mayúsculas")
        String curp,
    @NotBlank(message = "Este campo es obligatorio")
        @Pattern(regexp = "[+0-9 ()-]{7,15}", message = "Teléfono inválido")
        String telefono,
    @NotBlank(message = "Este campo es obligatorio")
        @Email(message = "Escribe un correo electrónico válido")
        @Size(max = 100, message = "El correo admite hasta 100 caracteres")
        String correo,
    @NotBlank(message = "Este campo es obligatorio")
        @Size(min = 12, max = 64, message = "Usa entre 12 y 64 caracteres")
        String password) {}
