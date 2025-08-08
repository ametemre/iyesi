package com.kurmez.iyesi.kayra;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orkhon runik kelimeleri Eski Türkçe fonotaktik kurallarına göre doğrulayan sınıf.
 * Geliştirmeler: hata kodları, detaylı geri bildirim, performans optimizasyonu, gelişmiş ünlü uyumu.
 */
public class BilgeKaan_validator {

    /**
     * Hata kodları: hangi kuralın ihlal edildiğini belirtir.
     */
    public enum Violation {
        NULL_OR_EMPTY,
        INVALID_RUNE,
        EXCEPTION_WORD,
        FORBIDDEN_INITIAL,
        SOFT_END,
        SYLLABLE_STRUCTURE,
        VOWEL_HARMONY,
        CONSONANT_CLUSTER,
        MAX_SYLLABLE_EXCEEDED
    }

    /**
     * Doğrulama sonucu: geçerlik durumu ve varsa hata mesajları.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> violations;

        public ValidationResult(boolean valid, List<String> violations) {
            this.valid = valid;
            this.violations = Collections.unmodifiableList(new ArrayList<>(violations));
        }

        public boolean isValid() {
            return valid;
        }

        public List<String> getViolations() {
            return violations;
        }
    }

    // Unicode tabanlı runik karakter setleri
    private static final Set<String> VOWELS = Set.of(
        "\uD800\uDC00", // 𐰀: a (kalın, düz)
        "\uD800\uDC03", // 𐰃: i/ı (belirsiz)
        "\uD800\uDC05", // 𐰅: e (ince, düz)
        "\uD800\uDC06", // 𐰆: o/u (kalın, yuvarlak)
        "\uD800\uDC07"  // 𐰇: ö/ü (ince, yuvarlak)
    );

    private static final Set<String> THICK_VOWELS = Set.of(
        "\uD800\uDC00", // 𐰀: a
        "\uD800\uDC06"  // 𐰆: o/u
    );

    private static final Set<String> THIN_VOWELS = Set.of(
        "\uD800\uDC05", // 𐰅: e
        "\uD800\uDC07"  // 𐰇: ö/ü
    );

    private static final Set<String> AMBIGUOUS_VOWELS = Set.of(
        "\uD800\uDC03"  // 𐰃: i/ı
    );

    // Yumuşak ünsüzler (kelime sonunda yasak)
    private static final Set<String> SOFT_CONSONANTS = new HashSet<>(Set.of(
        "\uD800\uDC0B", // 𐰋: b
        "\uD800\uDC13", // 𐰓: d
        "\uD800\uDC0F"  // 𐰏: g
    ));

    // Kelime başında yasaklı ünsüzler
    private static final Set<String> FORBIDDEN_INITIAL = new HashSet<>(Set.of(
        "\uD800\uDC2D"  // 𐰭: ŋ
    ));

    // Yasaklı ünsüz çiftleri
    private static final List<String> FORBIDDEN_CONSONANT_CLUSTERS = List.of(
        "\uD800\uDC0B\uD800\uDC13", // bd
        "\uD800\uDC13\uD800\uDC0B", // db
        "\uD800\uDC0F\uD800\uDC0B", // gb
        "\uD800\uDC0B\uD800\uDC0F"  // bg
    );

    private static final Pattern FORBIDDEN_CLUSTER_PATTERN;
    static {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < FORBIDDEN_CONSONANT_CLUSTERS.size(); i++) {
            sb.append(Pattern.quote(FORBIDDEN_CONSONANT_CLUSTERS.get(i)));
            if (i < FORBIDDEN_CONSONANT_CLUSTERS.size() - 1) sb.append("|");
        }
        FORBIDDEN_CLUSTER_PATTERN = Pattern.compile(sb.toString());
    }

    // İstisna kelimeler
    private static final Set<String> EXCEPTIONS = new HashSet<>(Set.of(
        "\uD800\uDC0B\uD800\uDC03\uD800\uDC3A", // bir
        "\uD800\uDC22\uD800\uDC05\uD800\uDC24"  // men
    ));

    // Geçerli Unicode aralığı ve maksimum hece sayısı
    private static final int MIN_RUNE_CODEPOINT = 0x10C00;
    private static final int MAX_RUNE_CODEPOINT = 0x10C4F;
    private static final int MAX_SYLLABLE_COUNT = 3;

    /**
     * Verilen runik kelimeyi doğrular ve detaylı sonuç döner.
     */
    public ValidationResult validate(String word) {
        List<String> violations = new ArrayList<>();

        if (word == null || word.isEmpty()) {
            violations.add("NULL_OR_EMPTY: Kelime boş veya null.");
            return new ValidationResult(false, violations);
        }

        if (!isValidRuneSequence(word)) {
            violations.add("INVALID_RUNE: Geçersiz runik karakter.");
            return new ValidationResult(false, violations);
        }

        if (EXCEPTIONS.contains(word)) {
            return new ValidationResult(true, violations);
        }

        List<String> runes = splitIntoRunes(word);

        if (FORBIDDEN_INITIAL.contains(runes.get(0))) {
            violations.add("FORBIDDEN_INITIAL: Kelime başında yasaklı karakter.");
        }
        if (SOFT_CONSONANTS.contains(runes.get(runes.size() - 1))) {
            violations.add("SOFT_END: Kelime sonunda yumuşak ünsüz.");
        }

        List<String> syllables = splitIntoSyllables(runes);
        if (syllables.size() > MAX_SYLLABLE_COUNT) {
            violations.add("MAX_SYLLABLE_EXCEEDED: Maksimum hece sayısı (" + MAX_SYLLABLE_COUNT + ") aşıldı.");
        }
        for (int i = 0; i < syllables.size(); i++) {
            if (!isValidSyllable(syllables.get(i))) {
                violations.add("SYLLABLE_STRUCTURE: " + (i+1) + ". hecede geçersiz yapı.");
            }
        }

        if (!checkVowelHarmony(runes)) {
            violations.add("VOWEL_HARMONY: Ünlü uyumu ihlali.");
        }

        if (!checkConsonantClusters(word)) {
            violations.add("CONSONANT_CLUSTER: Yasaklı ünsüz kümesi.");
        }

        boolean valid = violations.isEmpty();
        return new ValidationResult(valid, violations);
    }

    private boolean isValidRuneSequence(String word) {
        int idx = 0;
        while (idx < word.length()) {
            int cp = word.codePointAt(idx);
            if (cp < MIN_RUNE_CODEPOINT || cp > MAX_RUNE_CODEPOINT) return false;
            idx += Character.charCount(cp);
        }
        return true;
    }

    private List<String> splitIntoRunes(String word) {
        List<String> list = new ArrayList<>();
        int idx = 0;
        while (idx < word.length()) {
            int cp = word.codePointAt(idx);
            list.add(new String(Character.toChars(cp)));
            idx += Character.charCount(cp);
        }
        return list;
    }

    /**
     * Heceleme: her ünlü yeni hece başlatır, sonrasındaki ünsüzler heceye dahil edilir.
     */
    private List<String> splitIntoSyllables(List<String> runes) {
        List<String> sylls = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runes.size(); i++) {
            String r = runes.get(i);
            if (VOWELS.contains(r)) {
                if (sb.length() > 0) {
                    sylls.add(sb.toString()); sb.setLength(0);
                }
                sb.append(r);
                while (i+1 < runes.size() && !VOWELS.contains(runes.get(i+1))) {
                    sb.append(runes.get(++i));
                }
            } else {
                sb.append(r);
            }
        }
        if (sb.length() > 0) sylls.add(sb.toString());
        return sylls;
    }

    private boolean isValidSyllable(String syl) {
        List<String> rs = splitIntoRunes(syl);
        if (rs.size() > 3) return false;
        long vcount = rs.stream().filter(VOWELS::contains).count();
        return vcount == 1;
    }

    private boolean checkVowelHarmony(List<String> runes) {
        String harmony = null;
        for (int i = 0; i < runes.size(); i++) {
            String r = runes.get(i);
            if (THICK_VOWELS.contains(r)) {
                if (harmony == null) harmony = "thick";
                else if (!harmony.equals("thick")) return false;
            } else if (THIN_VOWELS.contains(r)) {
                if (harmony == null) harmony = "thin";
                else if (!harmony.equals("thin")) return false;
            } else if (AMBIGUOUS_VOWELS.contains(r)) {
                if (harmony == null) harmony = determineAmbiguousHarmony(runes, i);
            }
        }
        return true;
    }

    private String determineAmbiguousHarmony(List<String> runes, int pos) {
        for (int i = pos-1; i >= 0; i--) {
            String r = runes.get(i);
            if (THICK_VOWELS.contains(r)) return "thick";
            if (THIN_VOWELS.contains(r)) return "thin";
        }
        for (int i = pos+1; i < runes.size(); i++) {
            String r = runes.get(i);
            if (THICK_VOWELS.contains(r)) return "thick";
            if (THIN_VOWELS.contains(r)) return "thin";
        }
        return "thick";
    }

    private boolean checkConsonantClusters(String word) {
        Matcher m = FORBIDDEN_CLUSTER_PATTERN.matcher(word);
        return !m.find();
    }

    public static void main(String[] args) {
        BilgeKaan_validator v = new BilgeKaan_validator();
        String[] tests = {
            "\uD800\uDC0B\uD800\uDC03\uD800\uDC3A", // bir
            "\uD800\uDC22\uD800\uDC05\uD800\uDC24", // men
            "\uD800\uDC34\uD800\uDC0D\uD800\uDC23", // kağan
            "\uD800\uDC2D\uD800\uDC00",             // ŋa
            "\uD800\uDC34\uD800\uDC0D"              // kağ
        };

        for (String t : tests) {
            ValidationResult res = v.validate(t);
            System.out.println("Word: " + t + " -> valid=" + res.isValid() + ", violations=" + res.getViolations());
        }
    }
    // TODO: İstisna listesini dış JSON/config ile yönetecek mekanizma ekle
    // TODO: Onset cluster ayrımı geliştirmesi için ALLOWED_ONSET_CLUSTERS tablosu oluştur
}
