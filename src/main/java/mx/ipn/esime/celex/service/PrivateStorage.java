package mx.ipn.esime.celex.service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PrivateStorage {
  private final Path root;

  public PrivateStorage(@Value("${celex.storage}") String root) {
    this.root = Path.of(root).toAbsolutePath().normalize();
  }

  public static String format(byte[] bytes) {
    if (bytes.length >= 5
        && new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
      return "pdf";
    if (bytes.length >= 8
        && bytes[0] == (byte) 137
        && bytes[1] == 80
        && bytes[2] == 78
        && bytes[3] == 71
        && bytes[4] == 13
        && bytes[5] == 10
        && bytes[6] == 26
        && bytes[7] == 10) return "png";
    if (bytes.length >= 3
        && bytes[0] == (byte) 255
        && bytes[1] == (byte) 216
        && bytes[2] == (byte) 255) return "jpg";
    throw new IllegalArgumentException("Adjunta un archivo PDF, JPG o PNG válido.");
  }

  public String save(MultipartFile file) throws IOException {
    if (file.isEmpty() || file.getSize() > 2 * 1024 * 1024)
      throw new IllegalArgumentException("Cada archivo debe pesar entre 1 byte y 2 MB.");
    byte[] bytes = file.getBytes();
    return write(bytes, format(bytes));
  }

  public String write(byte[] bytes, String extension) throws IOException {
    Files.createDirectories(root);
    try {
      Files.setPosixFilePermissions(
          root, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    } catch (UnsupportedOperationException ignored) {
    }
    String key = UUID.randomUUID() + "." + extension;
    Path path = resolve(key);
    Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
    try {
      Files.setPosixFilePermissions(
          path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException ignored) {
    }
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isSynchronizationActive())
      org.springframework.transaction.support.TransactionSynchronizationManager
          .registerSynchronization(
              new org.springframework.transaction.support.TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                  if (status != STATUS_COMMITTED) remove(key);
                }
              });
    return key;
  }

  private Path resolve(String key) {
    if (!key.matches("[a-f0-9-]{36}\\.(pdf|png|jpg)"))
      throw new IllegalArgumentException("Archivo no válido");
    Path path = root.resolve(key).normalize();
    if (!path.getParent().equals(root)) throw new IllegalArgumentException("Archivo no válido");
    return path;
  }

  public byte[] read(String key) throws IOException {
    return Files.readAllBytes(resolve(key));
  }

  public void remove(String key) {
    try {
      Files.deleteIfExists(resolve(key));
    } catch (IOException ignored) {
    }
  }
}
