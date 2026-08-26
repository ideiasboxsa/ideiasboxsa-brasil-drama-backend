package br.com.brasildrama.media;

import br.com.brasildrama.catalog.DramaCatalogAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.*;

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
            var kind = MediaStorageService.ImageKind.valueOf(request.kind().trim().toUpperCase(Locale.ROOT));
            return ResponseEntity.ok(storage.presignDramaImage(dramaId, kind, request.contentType().trim()));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex);
        }
    }

    @PostMapping("/dramas/{dramaId}/images/confirm")
    ResponseEntity<?> confirmImage(@PathVariable UUID dramaId, @Valid @RequestBody ConfirmImageRequest request) {
        if (!catalog.exists(dramaId)) return ResponseEntity.notFound().build();
        try {
            var kind = MediaStorageService.ImageKind.valueOf(request.kind().trim().toUpperCase(Locale.ROOT));
            var image = storage.verifyDramaImage(dramaId, kind, request.objectKey().trim());
            catalog.attachImage(dramaId, kind, image.objectKey());
            return ResponseEntity.ok(new ConfirmImageResponse(kind.name(), image.objectKey(), image.contentType(), image.contentLength(), image.previewUrl()));
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) return ResponseEntity.status(409).body(new ApiError("Uploaded object was not found"));
            throw ex;
        } catch (IllegalArgumentException ex) {
            return badRequest(ex);
        }
    }

    @PostMapping("/dramas/{dramaId}/episodes/{episodeId}/video/multipart")
    ResponseEntity<?> startVideo(@PathVariable UUID dramaId, @PathVariable UUID episodeId, @Valid @RequestBody StartVideoRequest request) {
        if (!catalog.episodeBelongsToDrama(dramaId, episodeId)) return ResponseEntity.notFound().build();
        try {
            return ResponseEntity.ok(storage.startEpisodeVideo(dramaId, episodeId, request.contentType().trim(), request.fileSize()));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex);
        }
    }

    @PostMapping("/dramas/{dramaId}/episodes/{episodeId}/video/complete")
    ResponseEntity<?> completeVideo(@PathVariable UUID dramaId, @PathVariable UUID episodeId, @Valid @RequestBody CompleteVideoRequest request) {
        if (!catalog.episodeBelongsToDrama(dramaId, episodeId)) return ResponseEntity.notFound().build();
        try {
            var parts = request.parts().stream().map(p -> new MediaStorageService.UploadedPart(p.partNumber(), p.eTag())).toList();
            var video = storage.completeEpisodeVideo(dramaId, episodeId, request.uploadId(), request.objectKey(), parts);
            catalog.attachEpisodeVideo(dramaId, episodeId, video.objectKey());
            return ResponseEntity.ok(video);
        } catch (IllegalArgumentException ex) {
            return badRequest(ex);
        }
    }

    @PostMapping("/dramas/{dramaId}/episodes/{episodeId}/video/abort")
    ResponseEntity<?> abortVideo(@PathVariable UUID dramaId, @PathVariable UUID episodeId, @Valid @RequestBody AbortVideoRequest request) {
        if (!catalog.episodeBelongsToDrama(dramaId, episodeId)) return ResponseEntity.notFound().build();
        storage.abortEpisodeVideo(dramaId, episodeId, request.uploadId(), request.objectKey());
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<?> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    record PresignImageRequest(@NotBlank String kind, @NotBlank String contentType) {}
    record ConfirmImageRequest(@NotBlank String kind, @NotBlank String objectKey) {}
    record ConfirmImageResponse(String kind, String objectKey, String contentType, long contentLength, String previewUrl) {}
    record StartVideoRequest(@NotBlank String contentType, @Positive long fileSize) {}
    record UploadedPartRequest(@Min(1) int partNumber, @NotBlank String eTag) {}
    record CompleteVideoRequest(@NotBlank String uploadId, @NotBlank String objectKey, @NotEmpty List<@Valid UploadedPartRequest> parts) {}
    record AbortVideoRequest(@NotBlank String uploadId, @NotBlank String objectKey) {}
    record ApiError(String message) {}
}
