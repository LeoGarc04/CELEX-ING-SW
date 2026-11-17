package mx.ipn.esime.celex.service;

import java.text.Normalizer;
import java.util.*;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public final class EnglishLevels {
  private EnglishLevels() {}

  public static final List<String> ALL =
      Stream.of(
              Stream.of("Introductorio"),
              IntStream.rangeClosed(1, 5).mapToObj(n -> "Básico " + n),
              IntStream.rangeClosed(1, 5).mapToObj(n -> "Intermedio " + n),
              IntStream.rangeClosed(1, 6).mapToObj(n -> "Avanzado " + n))
          .flatMap(s -> s)
          .toList();

  public static boolean isEnglish(String language) {
    return "ingles"
        .equals(
            Normalizer.normalize(language.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT));
  }

  public static void requireValid(String language, String level) {
    if (isEnglish(language) && !ALL.contains(level))
      throw new IllegalArgumentException(
          "Selecciona un nivel de inglés válido: Introductorio, Básico 1–5, Intermedio 1–5 o"
              + " Avanzado 1–6.");
  }
}
