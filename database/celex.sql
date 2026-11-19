-- Base de datos del sistema CELEX
-- ESIME Culhuacán
-- Modelo de datos aprobado: 13 tablas

USE celex;


CREATE TABLE usuarios (
    id_usuario INT AUTO_INCREMENT PRIMARY KEY,
    nombre_completo VARCHAR(150) NOT NULL,
    curp VARCHAR(18) NULL UNIQUE,
    boleta VARCHAR(10) NULL UNIQUE,
    telefono VARCHAR(15) NOT NULL,
    correo VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    tipo_usuario VARCHAR(20) NOT NULL,
    estado_cuenta VARCHAR(20) DEFAULT 'Inactiva',
    intentos_fallidos INT DEFAULT 0,
    bloqueado_hasta DATETIME NULL,
    creado_en TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE roles_permisos (
    id_permiso INT AUTO_INCREMENT PRIMARY KEY,
    id_usuario INT,
    tipo_permiso VARCHAR(30) NOT NULL,
    FOREIGN KEY (id_usuario) REFERENCES usuarios(id_usuario)
) ENGINE=InnoDB;

CREATE TABLE verificaciones_codigo (
    id_codigo INT AUTO_INCREMENT PRIMARY KEY,
    id_usuario INT,
    codigo VARCHAR(10) NOT NULL,
    tipo_codigo VARCHAR(20) NOT NULL,
    expira_en DATETIME NOT NULL,
    usado BOOLEAN DEFAULT FALSE,
    FOREIGN KEY (id_usuario) REFERENCES usuarios(id_usuario)
) ENGINE=InnoDB;

CREATE TABLE convocatorias (
    id_convocatoria INT AUTO_INCREMENT PRIMARY KEY,
    nombre VARCHAR(100) NOT NULL,
    fecha_inicio_fila DATETIME NOT NULL,
    fecha_fin_fila DATETIME NOT NULL,
    capacidad_n_fila INT NOT NULL DEFAULT 100,
    estado VARCHAR(20) DEFAULT 'Programada'
) ENGINE=InnoDB;

CREATE TABLE idiomas (
    id_idioma INT AUTO_INCREMENT PRIMARY KEY,
    nombre VARCHAR(50) NOT NULL,
    activo BOOLEAN DEFAULT TRUE
) ENGINE=InnoDB;

CREATE TABLE grupos (
    id_grupo INT AUTO_INCREMENT PRIMARY KEY,
    id_convocatoria INT,
    id_idioma INT,
    nivel VARCHAR(20) NOT NULL,
    modalidad VARCHAR(30) NOT NULL,
    profesor VARCHAR(150) NOT NULL,
    aula VARCHAR(50) NOT NULL,
    dias VARCHAR(50) NOT NULL,
    horario VARCHAR(50) NOT NULL,
    cupo_total INT NOT NULL,
    cupo_disponible INT NOT NULL,
    estado VARCHAR(20) DEFAULT 'Activo',
    FOREIGN KEY (id_convocatoria) REFERENCES convocatorias(id_convocatoria),
    FOREIGN KEY (id_idioma) REFERENCES idiomas(id_idioma)
) ENGINE=InnoDB;

CREATE TABLE historial_academico (
    id_historial INT AUTO_INCREMENT PRIMARY KEY,
    id_usuario INT,
    id_idioma INT,
    nivel_actual VARCHAR(20) NOT NULL,
    calificacion INT NULL,
    acreditado BOOLEAN DEFAULT FALSE,
    requiere_examen_colocacion BOOLEAN DEFAULT FALSE,
    periodos_inactivo INT DEFAULT 0,
    registrado_por INT,
    fecha_registro DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (id_usuario) REFERENCES usuarios(id_usuario),
    FOREIGN KEY (id_idioma) REFERENCES idiomas(id_idioma),
    FOREIGN KEY (registrado_por) REFERENCES usuarios(id_usuario)
) ENGINE=InnoDB;

CREATE TABLE prioridades_usuario (
    id_prioridad INT AUTO_INCREMENT PRIMARY KEY,
    id_usuario INT,
    es_becario BOOLEAN DEFAULT FALSE,
    puntaje_beca INT DEFAULT 0,
    promedio_anterior DECIMAL(4,2) DEFAULT 0.00,
    es_alumno_nuevo BOOLEAN DEFAULT FALSE,
    registrado_por INT,
    FOREIGN KEY (id_usuario) REFERENCES usuarios(id_usuario),
    FOREIGN KEY (registrado_por) REFERENCES usuarios(id_usuario)
) ENGINE=InnoDB;

CREATE TABLE fila_virtual (
    id_turno INT AUTO_INCREMENT PRIMARY KEY,
    folio VARCHAR(30) NOT NULL UNIQUE,
    id_usuario INT,
    id_convocatoria INT,
    posicion_actual INT NOT NULL,
    nivel_prioridad_calculado INT NOT NULL,
    estado_turno VARCHAR(20) DEFAULT 'En espera',
    reserva_expira_en DATETIME NULL,
    fecha_ingreso DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (id_usuario) REFERENCES usuarios(id_usuario),
    FOREIGN KEY (id_convocatoria) REFERENCES convocatorias(id_convocatoria)
) ENGINE=InnoDB;

CREATE TABLE solicitudes_inscripcion (
    id_solicitud INT AUTO_INCREMENT PRIMARY KEY,
    folio VARCHAR(30),
    id_usuario INT,
    id_grupo INT,
    estado_inscripcion VARCHAR(30) DEFAULT 'Pendiente',
    monto_calculado DECIMAL(10,2) NOT NULL,
    estado_beca VARCHAR(20) DEFAULT 'Pendiente',
    fecha_envio DATETIME NULL,
    prorroga_expira_en DATETIME NULL,
    FOREIGN KEY (folio) REFERENCES fila_virtual(folio),
    FOREIGN KEY (id_usuario) REFERENCES usuarios(id_usuario),
    FOREIGN KEY (id_grupo) REFERENCES grupos(id_grupo)
) ENGINE=InnoDB;

CREATE TABLE documentos_comprobantes (
    id_documento INT AUTO_INCREMENT PRIMARY KEY,
    id_solicitud INT,
    numero_referencia VARCHAR(50) NOT NULL,
    fecha_pago DATE NOT NULL,
    importe DECIMAL(10,2) NOT NULL,
    url_comprobante_pago VARCHAR(255) NOT NULL,
    url_identificacion VARCHAR(255) NOT NULL,
    estatus_pago VARCHAR(30) DEFAULT 'Pendiente de revisión',
    observaciones_dictamen TEXT NULL,
    validado_por INT,
    fecha_dictamen DATETIME NULL,
    FOREIGN KEY (id_solicitud) REFERENCES solicitudes_inscripcion(id_solicitud),
    FOREIGN KEY (validado_por) REFERENCES usuarios(id_usuario)
) ENGINE=InnoDB;

CREATE TABLE comprobantes_oficiales (
    id_comprobante_oficial INT AUTO_INCREMENT PRIMARY KEY,
    id_solicitud INT,
    codigo_qr VARCHAR(255) NOT NULL,
    url_pdf VARCHAR(255) NOT NULL,
    fecha_emision DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (id_solicitud) REFERENCES solicitudes_inscripcion(id_solicitud)
) ENGINE=InnoDB;

CREATE TABLE bitacora_acciones (
    id_bitacora INT AUTO_INCREMENT PRIMARY KEY,
    id_usuario INT,
    accion VARCHAR(100) NOT NULL,
    detalles TEXT NULL,
    fecha_hora DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (id_usuario) REFERENCES usuarios(id_usuario)
) ENGINE=InnoDB;
