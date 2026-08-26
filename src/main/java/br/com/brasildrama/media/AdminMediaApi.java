package br.com.brasildrama.media;

import br.com.brasildrama.catalog.DramaCatalogAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.Locale;
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
            var kind = kind(request.kind());
            return ResponseEntity.ok(storage.presignDramaImage(dramaId, kind, request.contentType().trim()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
        }
    }

    @PostMapping("/dramas/{dramaId}/images/confirm")
    ResponseEntity<?> confirmImage(@PathVariable UUID dramaId, @Valid @RequestBody ConfirmImageRequest request) {
        if (!catalog.exists(dramaId)) return ResponseEntity.notFound().build();
        try {
            var kind = kind(request.kind());
            var image = storage.verifyDramaImage(dramaId, kind, request.objectKey().trim());
            catalog.attachImage(dramaId, kind, image.objectKey());
            return ResponseEntity.ok(new ConfirmImageResponse(kind.name(), image.objectKey(), image.contentType(), image.contentLength(), image.previewUrl()));
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) return ResponseEntity.status(409).body(new ApiError("Uploaded object was not found"));
            throw ex;
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
        }
    }

    private static MediaStorageService.ImageKind kind(String value) {
        return MediaStorageService.ImageKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    record PresignImageRequest(@NotBlank String kind, @NotBlank String contentType) {}
    record ConfirmImageRequest(@NotBlank String kind, @NotBlank String objectKey) {}
    record ConfirmImageResponse(String kind, String objectKey, String contentType, long contentLength, String previewUrl) {}
    record ApiError(String message) {}
}
