package mx.ipn.esime.celex.web;

import jakarta.validation.constraints.*;

public record ProfileDetails(
    @NotBlank(message = "Escribe tus apellidos y nombre(s)")
        @Size(max = 150, message = "El nombre admite hasta 150 caracteres")
        String nombre,
    @NotBlank(message = "Escribe tu teléfono")
        @Pattern(regexp = "[+0-9 ()-]{7,15}", message = "Teléfono inválido")
        String telefono) {}
