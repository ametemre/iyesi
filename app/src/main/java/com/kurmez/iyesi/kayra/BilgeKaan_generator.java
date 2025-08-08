package com.kurmez.iyesi.kayra;

import java.util.*;

/**
 * Orkhon runik kök seslerden türemiş kelimeler üreten sınıf.
 * Mevcut OldTurkicWordValidator sınıfını kullanarak yalnızca geçerli türemeler döner.
 * Hedef: Bilge Kağan dönemi kaybolmuş kelime ihtimallerini keşfetmek.
 */
public class BilgeKaan_generator {

    private final BilgeKaan_validator validator;
    private final List<String> roots;
    private final List<String> suffixes;

    /**
     * @param roots    Temel kök sesler (runik string olarak)
     * @param suffixes Derivasyon veya çekim ekleri (runik string olarak)
     */
    public BilgeKaan_generator(List<String> roots, List<String> suffixes) {
        this.validator = new BilgeKaan_validator();
        this.roots = new ArrayList<>(roots);
        this.suffixes = new ArrayList<>(suffixes);
    }

    /**
     * Tüm kök-suffix kombinasyonlarını deneyerek geçerli kelimeleri döner.
     * TODO: İleri düzeyde ünlü uyumuna göre suffix seçimi ekle
     */
    public List<String> generateAll() {
        List<String> results = new ArrayList<>();
        for (String root : roots) {
            for (String suffix : suffixes) {
                String candidate = root + suffix;
                BilgeKaan_validator.ValidationResult res = validator.validate(candidate);
                if (res.isValid()) {
                    results.add(candidate);
                }
            }
        }
        return results;
    }

    /**
     * Kökün farklı varyantlarına göre türetmeleri üretir.
     * Örnek: ünlü değiştirme, ünsüz benzeşmesi, vb.
     * TODO: Kaynak metin analizinden otomatik varyant çıkarımı ekle
     */
    public List<String> generateWithVariants() {
        List<String> results = new ArrayList<>();
        for (String root : roots) {
            List<String> variants = createRootVariants(root);
            for (String var : variants) {
                for (String suffix : suffixes) {
                    String candidate = var + suffix;
                    if (validator.validate(candidate).isValid()) {
                        results.add(candidate);
                    }
                }
            }
        }
        return results;
    }

    /**
     * Basit varyant üretici: belirsiz ünlü değişimi ve çift ünsüz kısalma.
     * TODO: Gerçek Orhun metinlerinden türetilmiş varyant listesiyle değiştir
     */
    private List<String> createRootVariants(String root) {
        List<String> variants = new ArrayList<>();
        variants.add(root);
        // Belirsiz ünlü 𐰃 (i/ı) yerine kalın ve ince versiyonlarını ekle
        if (root.contains("\uD800\uDC03")) {
            variants.add(root.replace("\uD800\uDC03", "\uD800\uDC00")); // 𐰃 -> 𐰀
            variants.add(root.replace("\uD800\uDC03", "\uD800\uDC05")); // 𐰃 -> 𐰅
        }
        // Çift ünsüzleri tek ünsüze indir
        variants.add(root.replaceAll("(\uD800\uDC0B)\\1+", "$1")); // b
        variants.add(root.replaceAll("(\uD800\uDC13)\\1+", "$1")); // d
        variants.add(root.replaceAll("(\uD800\uDC0F)\\1+", "$1")); // g
        return variants;
    }

    public static void main(String[] args) {
        // Örnek kökler ve ekler (Orhun runikleri olarak)
        List<String> sampleRoots = List.of(
            "\uD800\uDC00\uD800\uDC05", // 𐰀𐰅
            "\uD800\uDC03\uD800\uDC23"  // 𐰃𐰣
        );
        List<String> sampleSuffixes = List.of(
            "\uD800\uDC05", // 𐰅: -e
            "\uD800\uDC0B"  // 𐰋: -b
        );

        BilgeKaan_generator generator = new BilgeKaan_generator(sampleRoots, sampleSuffixes);
        System.out.println("Tüm geçerli türemeler:");
        generator.generateAll().forEach(w -> System.out.println("- " + w));
        System.out.println("Varyantlarla türemeler:");
        generator.generateWithVariants().forEach(w -> System.out.println("* " + w));
    }

    // TODO: Büyük kök listesi ve ekleri dış kaynaktan oku
    // TODO: Filtreleme için frekans/istatistik tabanı ekle
}
