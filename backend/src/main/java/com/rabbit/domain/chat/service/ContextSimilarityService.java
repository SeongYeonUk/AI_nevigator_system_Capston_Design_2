package com.rabbit.domain.chat.service;

import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ContextSimilarityService {

    private static final Pattern SPLIT_PATTERN = Pattern.compile(
            "[,/;|]|\\band\\b|\\bor\\b|그리고|및",
            Pattern.CASE_INSENSITIVE
    );
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "what", "how", "why", "about", "this", "that",
            "설명", "알려줘", "알려", "자세히", "예시", "정의", "개념", "방법", "차이", "비교"
    );
    private static final Map<String, Set<String>> TOPIC_HINTS = buildTopicHints();

    private final EmbeddingModel embeddingModel;

    public double score(String query, String topic, List<String> samples) {
        List<String> merged = merge(topic, samples);
        double heuristic = heuristicScore(query, merged);
        try {
            double embedding = embeddingScore(query, merged);
            return (embedding * 0.75) + (heuristic * 0.25);
        } catch (Exception ignored) {
            return heuristic;
        }
    }

    public double relationshipScore(String query, List<String> contextSamples) {
        double heuristic = heuristicScore(query, contextSamples);
        try {
            double embedding = embeddingScore(query, contextSamples);
            return (embedding * 0.8) + (heuristic * 0.2);
        } catch (Exception ignored) {
            return heuristic;
        }
    }

    public double centroidScore(String query, List<String> samples) {
        if (query == null || query.isBlank() || samples == null || samples.isEmpty()) {
            return 0.0;
        }

        List<String> filtered = new ArrayList<>();
        for (String sample : samples) {
            if (sample != null && !sample.isBlank()) {
                filtered.add(sample);
            }
        }
        if (filtered.isEmpty()) {
            return 0.0;
        }

        double heuristic = heuristicScore(query, filtered);
        try {
            float[] queryVector = embeddingModel.embed(query).content().vector();
            float[] centroid = averagedEmbedding(filtered);
            if (centroid.length == 0) {
                return heuristic;
            }
            return (cosineSimilarity(queryVector, centroid) * 0.85) + (heuristic * 0.15);
        } catch (Exception ignored) {
            return heuristic;
        }
    }

    public double hintOverlapScore(String query, String topicDescriptor) {
        Set<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return 0.0;
        }
        Set<String> hintTokens = resolvedHints(topicDescriptor);
        if (hintTokens.isEmpty()) {
            return 0.0;
        }

        int matched = 0;
        for (String token : queryTokens) {
            if (hintTokens.contains(token)) {
                matched++;
            }
        }
        return matched / (double) Math.max(queryTokens.size(), 1);
    }

    public boolean stronglyMatchesTopic(String query, String topicDescriptor) {
        String normalizedQuery = normalizeCompact(query);
        if (normalizedQuery.isBlank()) {
            return false;
        }

        for (String hint : resolvedHints(topicDescriptor)) {
            String normalizedHint = normalizeCompact(hint);
            if (normalizedHint.length() < 2) {
                continue;
            }
            if (normalizedQuery.contains(normalizedHint)) {
                return true;
            }
        }
        return false;
    }

    private double embeddingScore(String query, List<String> samples) {
        String branchText = String.join("\n", samples);
        if (branchText.isBlank()) {
            return 0.0;
        }

        float[] queryVector = embeddingModel.embed(query).content().vector();
        float[] branchVector = embeddingModel.embed(branchText).content().vector();
        return cosineSimilarity(queryVector, branchVector);
    }

    private double heuristicScore(String query, List<String> samples) {
        Set<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return 0.0;
        }

        Set<String> sampleTokens = new LinkedHashSet<>();
        for (String sample : samples) {
            sampleTokens.addAll(tokenize(sample));
        }
        if (sampleTokens.isEmpty()) {
            return 0.0;
        }

        int matched = 0;
        for (String token : queryTokens) {
            if (sampleTokens.contains(token)) {
                matched++;
            }
        }
        return matched / (double) Math.max(queryTokens.size(), 1);
    }

    private float[] averagedEmbedding(List<String> samples) {
        float[] sum = null;
        int count = 0;

        for (String sample : samples) {
            if (sample == null || sample.isBlank()) {
                continue;
            }

            float[] vector = embeddingModel.embed(sample).content().vector();
            if (vector == null || vector.length == 0) {
                continue;
            }

            if (sum == null) {
                sum = new float[vector.length];
            }
            if (vector.length != sum.length) {
                continue;
            }

            for (int i = 0; i < vector.length; i++) {
                sum[i] += vector[i];
            }
            count++;
        }

        if (sum == null || count == 0) {
            return new float[0];
        }
        for (int i = 0; i < sum.length; i++) {
            sum[i] = sum[i] / count;
        }
        return sum;
    }

    private List<String> merge(String topic, List<String> samples) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (topic != null && !topic.isBlank()) {
            merged.add(topic);
            for (String hint : resolvedHints(topic)) {
                merged.add(hint);
            }
        }
        if (samples != null) {
            for (String sample : samples) {
                if (sample != null && !sample.isBlank()) {
                    merged.add(sample);
                }
            }
        }
        return new ArrayList<>(merged);
    }

    private Set<String> resolvedHints(String topicDescriptor) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (topicDescriptor == null || topicDescriptor.isBlank()) {
            return hints;
        }

        hints.addAll(extractRawHints(topicDescriptor));
        String normalizedDescriptor = normalizeCompact(topicDescriptor);
        for (Map.Entry<String, Set<String>> entry : TOPIC_HINTS.entrySet()) {
            String normalizedKey = normalizeCompact(entry.getKey());
            if (!normalizedKey.isBlank() && normalizedDescriptor.contains(normalizedKey)) {
                hints.addAll(entry.getValue());
            }
        }
        return hints;
    }

    private Set<String> extractRawHints(String topicDescriptor) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (topicDescriptor == null || topicDescriptor.isBlank()) {
            return hints;
        }

        for (String part : SPLIT_PATTERN.split(topicDescriptor)) {
            String cleaned = part == null ? "" : part.trim();
            if (cleaned.length() >= 2) {
                hints.add(cleaned);
            }
        }
        return hints;
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }

        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) {
            return Set.of();
        }

        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split(" ")) {
            String cleaned = token.trim();
            if (cleaned.length() < 2 || STOP_WORDS.contains(cleaned)) {
                continue;
            }
            tokens.add(cleaned);
        }
        return tokens;
    }

    private String normalizeCompact(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0.0;
        }

        double dot = 0.0;
        double aNorm = 0.0;
        double bNorm = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            aNorm += a[i] * a[i];
            bNorm += b[i] * b[i];
        }
        if (aNorm == 0.0 || bNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(aNorm) * Math.sqrt(bNorm));
    }

    private static Map<String, Set<String>> buildTopicHints() {
        Map<String, Set<String>> hints = new HashMap<>();
        hints.put("데이터베이스", setOf("정규화", "nf", "3nf", "bcnf", "sql", "트랜잭션", "조인", "인덱스", "db"));
        hints.put("운영체제", setOf("데드락", "페이징", "프로세스", "스레드", "동시성", "메모리", "os"));
        hints.put("소프트웨어공학", setOf("요구사항", "스크럼", "칸반", "테스트", "설계", "품질", "sdlc"));
        hints.put("물리학", setOf("뉴턴", "관성", "힘", "가속도", "운동법칙", "에너지", "파동"));
        hints.put("화학", setOf("공유결합", "이온결합", "원자", "분자", "전기음성도", "주기율표", "반응"));
        hints.put("생물학", setOf("광합성", "세포", "유전", "진화", "생태계", "효소", "호흡"));
        return hints;
    }

    private static Set<String> setOf(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }
}
