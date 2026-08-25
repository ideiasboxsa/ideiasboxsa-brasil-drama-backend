package br.com.brasildrama.media;

import br.com.brasildrama.catalog.DramaCatalogAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/media")
class AdminMediaApi {
    private final MediaStorageService storage;
    private final DramaCatalogAccess catalog;

    AdminMediaApi(MediaStorageService storage, DramaCatalogAccess catalog) {
        this.storage = storage;
        this.catalog = catalog;
    }

    @PostMapping("/dramas/{dramaId}/images/presign")
    ResponseEntity<?> presignImage(@PathVariable UUID dramaId, @Valid @RequestBody PresignImageRequest request) {
        if (!catalog.exists(dramaId)) return ResponseEntity.notFound().build();
        try {
            var kind = MediaStorageService.ImageKind.valueOf(request.kind().trim().toUpperCase());
            return ResponseEntity.ok(storage.presignDramaImage(dramaId, kind, request.contentType().trim()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
        }
    }

    record PresignImageRequest(@NotBlank String kind, @NotBlank String contentType) {}
    record ApiError(String message) {}
}
