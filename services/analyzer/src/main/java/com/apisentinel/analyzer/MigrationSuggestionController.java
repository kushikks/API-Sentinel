package com.apisentinel.analyzer;

import com.apisentinel.shared.ApiErrors;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MigrationSuggestionController {
    private final MigrationSuggestions suggestions;

    MigrationSuggestionController(MigrationSuggestions suggestions) {
        this.suggestions = suggestions;
    }

    @PostMapping("/analyses/{analysisId}/migration-suggestions")
    ResponseEntity<MigrationSuggestions.Result> generate(@AuthenticationPrincipal Jwt user, @PathVariable UUID analysisId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(suggestions.generate(user, analysisId));
    }

    @ExceptionHandler(MigrationSuggestionFailure.class)
    ResponseEntity<ProblemDetail> generationFailure(MigrationSuggestionFailure error) {
        var problem = ApiErrors.body(error.status, error.code, error.getMessage());
        var response = ResponseEntity.status(error.status).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (error.retryAfterSeconds != null) {
            problem.setProperty("retryAfterSeconds", error.retryAfterSeconds);
            response.header(HttpHeaders.RETRY_AFTER, error.retryAfterSeconds.toString());
        }
        return response.body(problem);
    }
}
