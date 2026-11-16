package br.com.tuvani.murilo;

import java.text.Normalizer;
import java.util.Locale;

/** Utilitários de texto. */
public final class Texto {

    private Texto() {
    }

    /** Remove acentos, passa para maiúsculas e normaliza espaços, para comparar descrições. */
    public static String normalizar(String s) {
        if (s == null) {
            return "";
        }
        return semAcento(s).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** Remove só os acentos. */
    public static String semAcento(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
