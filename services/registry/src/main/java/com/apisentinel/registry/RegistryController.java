package com.apisentinel.registry;

import com.apisentinel.openapi.OpenApiDocument;
import com.apisentinel.shared.Paging;
import com.apisentinel.shared.Problem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
class RegistryController {
    private final Catalog catalog;
    private final AnalyzerStatus analyzer;

    RegistryController(Catalog catalog, AnalyzerStatus analyzer) {
        this.catalog = catalog;
        this.analyzer = analyzer;
    }

    @PostMapping("/apis")
    ResponseEntity<Catalog.Api> create(@AuthenticationPrincipal Jwt user, @Valid @RequestBody CreateApi request) {
        var api = catalog.create(user, request.name(), request.description(), request.baseUrl());
        return ResponseEntity.created(URI.create("/apis/" + api.id())).body(api);
    }

    @GetMapping("/apis")
    Paging.Page<Catalog.Api> list(@AuthenticationPrincipal Jwt user,
                                @RequestParam(defaultValue = "") @Size(max = 100) String q,
                                @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return catalog.list(user, q, new Paging(page, size));
    }

    @GetMapping("/apis/{apiId}")
    Catalog.Api get(@AuthenticationPrincipal Jwt user, @PathVariable UUID apiId) {
        return catalog.get(user, apiId);
    }

    @PatchMapping("/apis/{apiId}")
    Catalog.Api update(@AuthenticationPrincipal Jwt user, @PathVariable UUID apiId, @Valid @RequestBody UpdateApi request) {
        return catalog.update(user, apiId, request.name(), request.description(), request.baseUrl());
    }

    @PostMapping(path = "/apis/{apiId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Catalog.Upload> upload(@AuthenticationPrincipal Jwt user, @PathVariable UUID apiId,
                                         @RequestParam @NotBlank @Size(max = 80) String versionLabel,
                                         @RequestParam MultipartFile file, @RequestParam(defaultValue = "true") boolean analyze) {
        catalog.get(user, apiId);
        try {
            var format = OpenApiDocument.Format.file(file.getOriginalFilename());
            if (file.getSize() > OpenApiDocument.MAX_BYTES) {
                throw new OpenApiDocument.Invalid("SPEC_TOO_LARGE", "A specification may be at most 1 MiB.");
            }
            var bytes = file.getBytes();
            OpenApiDocument.read(bytes, format);
            var upload = catalog.upload(user, apiId, versionLabel, bytes, format, analyze);
            return ResponseEntity.created(URI.create("/apis/" + apiId + "/versions/" + upload.version().id())).body(upload);
        } catch (OpenApiDocument.Invalid error) {
            var status = "SPEC_TOO_LARGE".equals(error.code()) ? HttpStatus.PAYLOAD_TOO_LARGE : HttpStatus.UNPROCESSABLE_ENTITY;
            throw new Problem(status, error.code(), error.getMessage());
        } catch (IOException error) {
            throw new Problem(HttpStatus.BAD_REQUEST, "SPEC_UNREADABLE", "The uploaded file could not be read.");
        }
    }

    @GetMapping("/apis/{apiId}/versions")
    Paging.Page<Catalog.Version> versions(@AuthenticationPrincipal Jwt user, @PathVariable UUID apiId,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return catalog.versions(user, apiId, new Paging(page, size));
    }

    @GetMapping("/apis/{apiId}/versions/{versionId}")
    Catalog.Version version(@AuthenticationPrincipal Jwt user, @PathVariable UUID apiId, @PathVariable UUID versionId) {
        return catalog.version(user, apiId, versionId);
    }

    @GetMapping("/apis/{apiId}/versions/{versionId}/spec")
    ResponseEntity<byte[]> spec(@AuthenticationPrincipal Jwt user, @PathVariable UUID apiId, @PathVariable UUID versionId) {
        return download(versionId, catalog.spec(user, apiId, versionId));
    }

    @PostMapping("/apis/{apiId}/versions/{versionId}/analyze")
    ResponseEntity<Catalog.Submission> analyze(@AuthenticationPrincipal Jwt user, @PathVariable UUID apiId,
                                              @PathVariable UUID versionId, @RequestBody(required = false) Analyze request) {
        var submission = catalog.submit(user, apiId, versionId, request == null ? null : request.baselineVersionId());
        return ResponseEntity.accepted().location(URI.create("/analyses/" + submission.analysisId())).body(submission);
    }

    @GetMapping("/apis/{apiId}/versions/{versionId}/analysis")
    AnalyzerStatus.Latest latest(@AuthenticationPrincipal Jwt user, @PathVariable UUID apiId, @PathVariable UUID versionId) {
        return analyzer.latest(catalog.latest(user, apiId, versionId));
    }

    @GetMapping("/internal/apis/{apiId}")
    Catalog.Api internalApi(@PathVariable UUID apiId) { return catalog.metadata(apiId); }

    @GetMapping("/internal/apis/{apiId}/versions/{versionId}/spec")
    ResponseEntity<byte[]> internalSpec(@PathVariable UUID apiId, @PathVariable UUID versionId) {
        return download(versionId, catalog.spec(apiId, versionId));
    }

    private static ResponseEntity<byte[]> download(UUID version, Catalog.Artifact artifact) {
        String extension = artifact.contentType().equals("application/json") ? "json" : "yaml";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(artifact.contentType()))
                .cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"openapi-" + version + "." + extension + "\"").body(artifact.content());
    }

    record CreateApi(@NotBlank @Size(max = 100) String name, @Size(max = 1000) String description,
                     @Size(max = 2048) String baseUrl) {}
    record UpdateApi(@Size(max = 100) String name, @Size(max = 1000) String description,
                     @Size(max = 2048) String baseUrl) {}
    record Analyze(UUID baselineVersionId) {}
}
