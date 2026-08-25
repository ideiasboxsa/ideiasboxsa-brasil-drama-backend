package br.com.brasildrama.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.text.Normalizer;
import java.util.*;

@RestController
@RequestMapping("/v1/admin/dramas")
class AdminDramaApi {
    private final DramaRepository dramas;

    AdminDramaApi(DramaRepository dramas) {
        this.dramas = dramas;
    }

    @GetMapping
    List<AdminDramaSummary> list() {
        return dramas.findAllByOrderByTitleAsc().stream().map(AdminDramaApi::summary).toList();
    }

    @PostMapping
    ResponseEntity<?> create(@Valid @RequestBody CreateDramaRequest request) {
        var slug = uniqueSlug(request.slug() == null || request.slug().isBlank() ? request.title() : request.slug());
        var drama = new DramaEntity();
        drama.title = request.title().trim();
        drama.synopsis = request.synopsis().trim();
        drama.genre = request.genre().trim();
        drama.coverUrl = null;
        drama.slug = slug;
        drama.status = DramaStatus.DRAFT;
        dramas.saveAndFlush(drama);
        return ResponseEntity.status(201).body(summary(drama));
    }

    private String uniqueSlug(String source) {
        var base = slugify(source);
        var candidate = base;
        var suffix = 2;
        while (dramas.existsBySlugIgnoreCase(candidate)) candidate = base + "-" + suffix++;
        return candidate;
    }

    private static String slugify(String source) {
        var normalized = Normalizer.normalize(source, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
        return normalized.isBlank() ? UUID.randomUUID().toString() : normalized;
    }

    private static AdminDramaSummary summary(DramaEntity drama) {
        return new AdminDramaSummary(drama.id, drama.title, drama.slug, drama.genre, drama.status.name(), drama.coverUrl, drama.updatedAt);
    }

    record CreateDramaRequest(
        @NotBlank @Size(max = 180) String title,
        @NotBlank @Size(max = 4000) String synopsis,
        @NotBlank @Size(max = 120) String genre,
        @Size(max = 180) String slug
    ) {}

    record AdminDramaSummary(UUID id, String title, String slug, String genre, String status, String coverUrl, java.time.Instant updatedAt) {}
}
