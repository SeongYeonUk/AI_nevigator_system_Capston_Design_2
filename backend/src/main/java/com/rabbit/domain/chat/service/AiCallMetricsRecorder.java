package com.rabbit.domain.chat.service;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.stereotype.Component;

@Component
public class AiCallMetricsRecorder implements ChatModelListener {

    private final ThreadLocal<Boolean> recording = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<TokenUsage> latestUsage = new ThreadLocal<>();

    public Scope start() {
        recording.set(true);
        latestUsage.remove();
        return new Scope(this);
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        if (!Boolean.TRUE.equals(recording.get()) || responseContext == null || responseContext.response() == null) {
            return;
        }
        latestUsage.set(responseContext.response().tokenUsage());
    }

    private AiCallMetrics snapshot(long responseTimeMs, String prompt, String answer) {
        TokenUsage usage = latestUsage.get();
        Integer inputTokens = usage != null ? usage.inputTokenCount() : null;
        Integer outputTokens = usage != null ? usage.outputTokenCount() : null;
        Integer totalTokens = usage != null ? usage.totalTokenCount() : null;

        if (totalTokens == null) {
            inputTokens = estimateTokens(prompt);
            outputTokens = estimateTokens(answer);
            totalTokens = inputTokens + outputTokens;
        }

        return new AiCallMetrics(inputTokens, outputTokens, totalTokens, responseTimeMs);
    }

    private void stop() {
        recording.remove();
        latestUsage.remove();
    }

    private static int estimateTokens(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(normalized.length() / 3.5));
    }

    public record AiCallMetrics(
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens,
            Long responseTimeMs
    ) {
    }

    public static final class Scope implements AutoCloseable {
        private final AiCallMetricsRecorder recorder;

        private Scope(AiCallMetricsRecorder recorder) {
            this.recorder = recorder;
        }

        public AiCallMetrics snapshot(long responseTimeMs, String prompt, String answer) {
            return recorder.snapshot(responseTimeMs, prompt, answer);
        }

        @Override
        public void close() {
            recorder.stop();
        }
    }
}
