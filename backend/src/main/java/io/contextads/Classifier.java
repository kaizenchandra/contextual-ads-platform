package io.contextads;

import java.util.*;

import org.jsoup.Jsoup;

public final class Classifier {
    static final Map<String, List<String>> TAXONOMY = Map.of("automotive.ev", List.of("electric", "vehicle", "charging", "battery", "ev"), "technology.java", List.of("java", "spring", "jvm", "code", "developer"), "technology.cloud", List.of("cloud", "kubernetes", "infrastructure", "server"), "lifestyle.travel", List.of("travel", "hotel", "destination", "holiday"), "lifestyle.fitness", List.of("fitness", "exercise", "gym", "training"), "lifestyle.home", List.of("home", "renovation", "kitchen", "garden"));

    public Context analyze(String html) {
        if (html == null || html.length() > 100000)
            throw new IllegalArgumentException("Content must be at most 100000 characters");
        var doc = Jsoup.parse(html);
        doc.select("script,style,nav,footer,aside").remove();
        String text = doc.text().toLowerCase(Locale.ROOT);
        var words = Arrays.stream(text.split("[^a-z0-9]+")).filter(x -> x.length() > 2).toList();
        var counts = new HashMap<String, Integer>();
        words.forEach(t -> counts.merge(t, 1, Integer::sum));
        String best = "unknown";
        double max = 0;
        for (var entry : new TreeMap<>(TAXONOMY).entrySet()) {
            double score = entry.getValue().stream().mapToDouble(t -> Math.min(counts.getOrDefault(t, 0), 3)).sum();
            if (score > max) {
                max = score;
                best = entry.getKey();
            }
        }
        boolean safe = !text.matches("(?s).*\\b(adult|violence|hate|weapon)\\b.*");
        return new Context(max < 2 ? "unknown" : best, Math.min(1, max / 6), counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(12).map(Map.Entry::getKey).toList(), safe);
    }

    public record Context(String category, double confidence, List<String> keywords, boolean safe) {
    }
}
