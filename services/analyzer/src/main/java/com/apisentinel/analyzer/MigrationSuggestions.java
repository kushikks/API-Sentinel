package com.apisentinel.analyzer;

import com.apisentinel.openapi.Compatibility.Severity;
import com.apisentinel.shared.Paging;
import com.apisentinel.shared.SecurityPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
class MigrationSuggestions {
    private static final int MAX_FINDINGS = 5;
    private static final String TYPE = "(any|never|unknown|object|array|string|integer|number|boolean|null)";
    private static final Pattern SAFE_TYPES = Pattern.compile(TYPE + "( \\| " + TYPE + "){0,7}");

    private final AnalysisQueries query;
    private final MigrationSuggestionLimits limits;
    private final GeminiMigrationClient gemini;
    private final Semaphore inFlight = new Semaphore(2);

    MigrationSuggestions(AnalysisQueries query, MigrationSuggestionLimits limits, GeminiMigrationClient gemini) {
        this.query = query;
        this.limits = limits;
        this.gemini = gemini;
    }

    Result generate(Jwt user, UUID analysisId) {
        var job = query.get(user, analysisId);
        if (job.status() != JobStore.State.COMPLETED) {
            throw new MigrationSuggestionFailure(HttpStatus.CONFLICT, "ANALYSIS_NOT_COMPLETED",
                    "Migration suggestions require a completed analysis.");
        }
        int eligible = job.breakingCount() + job.potentiallyBreakingCount();
        if (eligible == 0) throw noFindings();
        if (!gemini.configured()) {
            throw new MigrationSuggestionFailure(HttpStatus.SERVICE_UNAVAILABLE, "GEMINI_NOT_CONFIGURED",
                    "AI suggestions are unavailable because Gemini is not configured on the Analyzer.");
        }

        List<JobStore.Finding> selected = new ArrayList<>(query.findings(job, Severity.BREAKING, null,
                new Paging(0, MAX_FINDINGS)).items());
        if (selected.size() < MAX_FINDINGS && job.potentiallyBreakingCount() > 0) {
            selected.addAll(query.findings(job, Severity.POTENTIALLY_BREAKING, null,
                    new Paging(0, MAX_FINDINGS - selected.size())).items());
        }
        if (selected.isEmpty()) throw noFindings();

        var input = new ArrayList<GeminiMigrationClient.FindingContext>();
        for (int i = 0; i < selected.size(); i++) {
            var finding = selected.get(i);
            input.add(new GeminiMigrationClient.FindingContext("F" + (i + 1), finding.severity().name(),
                    finding.category(), finding.httpMethod(), bounded(finding.path(), 200), bounded(finding.jsonPath(), 320),
                    schemaType(finding.category(), finding.oldValue()), schemaType(finding.category(), finding.newValue())));
        }

        if (!inFlight.tryAcquire()) {
            throw new MigrationSuggestionFailure(HttpStatus.TOO_MANY_REQUESTS, "MIGRATION_BUSY",
                    "The Analyzer is already generating migration suggestions. Try again shortly.", 5);
        }
        try {
            limits.reserve(SecurityPolicy.actor(user));
            var advice = gemini.generate(input);
            var result = new ArrayList<Suggestion>();
            for (int i = 0; i < selected.size(); i++) {
                var finding = selected.get(i);
                var guidance = advice.get(input.get(i).findingRef());
                result.add(new Suggestion(finding.findingId(), finding.severity(), finding.category(),
                        finding.httpMethod(), finding.path(), finding.jsonPath(), guidance.explanation(),
                        guidance.affectedClientCode(), guidance.recommendedChanges(), guidance.verificationSteps()));
            }
            return new Result(analysisId, "GEMINI", gemini.model(), false, eligible, List.copyOf(result));
        } catch (MigrationSuggestionFailure failure) {
            if ("GEMINI_RATE_LIMITED".equals(failure.code)) limits.deferProvider(failure.retryAfterSeconds);
            throw failure;
        } finally {
            inFlight.release();
        }
    }

    private static String bounded(String text, int limit) {
        if (text == null) return null;
        String clean = text.replaceAll("[\\p{Cc}\\p{Cf}]", "");
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }

    private static String schemaType(String category, String value) {
        return category.endsWith("_SCHEMA_TYPE_CHANGED") && value != null && SAFE_TYPES.matcher(value).matches()
                ? value : null;
    }

    private static MigrationSuggestionFailure noFindings() {
        return new MigrationSuggestionFailure(HttpStatus.UNPROCESSABLE_ENTITY, "NO_MIGRATION_FINDINGS",
                "This analysis has no breaking or potentially breaking findings to review.");
    }

    record Result(UUID analysisId, String source, String model, boolean verified, int eligibleFindingCount,
                  List<Suggestion> suggestions) {}

    record Suggestion(UUID findingId, Severity severity, String category, String httpMethod, String path, String jsonPath,
                      String explanation, String affectedClientCode, List<String> recommendedChanges,
                      List<String> verificationSteps) {}
}
