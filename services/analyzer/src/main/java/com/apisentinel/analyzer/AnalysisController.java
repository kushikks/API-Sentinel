package com.apisentinel.analyzer;

import com.apisentinel.openapi.Compatibility;
import com.apisentinel.shared.Paging;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class AnalysisController {
    private final AnalysisQueries query;

    AnalysisController(AnalysisQueries query) { this.query = query; }

    @GetMapping("/analyses")
    Paging.Page<JobStore.Job> list(@AuthenticationPrincipal Jwt user,
                                 @RequestParam(required = false) UUID apiId,
                                 @RequestParam(required = false) JobStore.State status,
                                 @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return query.list(user, apiId, status, new Paging(page, size));
    }

    @GetMapping("/analyses/{analysisId}")
    JobStore.Job get(@AuthenticationPrincipal Jwt user, @PathVariable UUID analysisId) {
        return query.get(user, analysisId);
    }

    @GetMapping("/analyses/{analysisId}/findings")
    Paging.Page<JobStore.Finding> findings(@AuthenticationPrincipal Jwt user, @PathVariable UUID analysisId,
                                         @RequestParam(required = false) Compatibility.Severity severity,
                                         @RequestParam(required = false) @Size(max = 80) String category,
                                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return query.findings(query.get(user, analysisId), severity, category, new Paging(page, size));
    }

    @GetMapping("/analyses/{analysisId}/summary")
    AnalysisQueries.Summary summary(@AuthenticationPrincipal Jwt user, @PathVariable UUID analysisId) {
        return AnalysisQueries.summary(query.get(user, analysisId));
    }

    @GetMapping("/analyses/{analysisId}/report")
    ResponseEntity<byte[]> report(@AuthenticationPrincipal Jwt user, @PathVariable UUID analysisId) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"compatibility-" + analysisId + ".json\"")
                .body(query.report(query.get(user, analysisId)));
    }

    @GetMapping("/internal/analyses/{analysisId}/status")
    AnalysisQueries.Status internalStatus(@PathVariable UUID analysisId) {
        return AnalysisQueries.status(query.internal(analysisId));
    }

    @GetMapping("/internal/analyses/{analysisId}/findings")
    Paging.Page<JobStore.Finding> internalFindings(@PathVariable UUID analysisId,
                                                 @RequestParam(required = false) Compatibility.Severity severity,
                                                 @RequestParam(required = false) @Size(max = 80) String category,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return query.findings(query.internal(analysisId), severity, category, new Paging(page, size));
    }

    @GetMapping("/internal/analyses/{analysisId}/summary")
    AnalysisQueries.Summary internalSummary(@PathVariable UUID analysisId) {
        return AnalysisQueries.summary(query.internal(analysisId));
    }
}
