-- Añadir boleta para alumnos IPN; conservar CURP para público general.
-- Requiere respaldo y servicio detenido. Aplicar una sola vez.
ALTER TABLE usuarios
    MODIFY COLUMN curp VARCHAR(18) NULL,
    ADD COLUMN boleta VARCHAR(10) NULL UNIQUE AFTER curp;
