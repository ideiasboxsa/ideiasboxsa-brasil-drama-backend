package br.com.brasildrama.media;

import br.com.brasildrama.catalog.DramaRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/media")
class AdminMediaApi {
    private final MediaStorageService storage;
    private final DramaRepository dramas;

    AdminMediaApi(MediaStorageService storage, DramaRepository dramas) {
        this.storage = storage;
        this.dramas = dramas;
    }

    @PostMapping("/dramas/{dramaId}/images/presign")
    ResponseEntity<?> presignImage(@PathVariable UUID dramaId, @Valid @RequestBody PresignImageRequest request) {
        if (!dramas.existsById(dramaId)) return ResponseEntity.notFound().build();
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
