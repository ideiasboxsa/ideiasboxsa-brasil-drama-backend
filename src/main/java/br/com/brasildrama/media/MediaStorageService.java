package br.com.brasildrama.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

@Service
public class MediaStorageService implements AutoCloseable {
    private final String bucket;
    private final Duration signatureDuration;
    private final S3Presigner presigner;

    public MediaStorageService(
        @Value("${brasil-drama.media.bucket}") String bucket,
        @Value("${brasil-drama.media.region}") String region,
        @Value("${brasil-drama.media.presign-minutes:15}") long presignMinutes
    ) {
        this.bucket = bucket;
        this.signatureDuration = Duration.ofMinutes(presignMinutes);
        this.presigner = S3Presigner.builder().region(Region.of(region)).build();
    }

    public PresignedUpload presignDramaImage(UUID dramaId, ImageKind kind, String contentType) {
        validateImageContentType(contentType);
        var extension = switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/jpeg" -> "jpg";
            default -> throw new IllegalArgumentException("Unsupported image type");
        };
        var key = "dramas/%s/%s/%s.%s".formatted(dramaId, kind.path, UUID.randomUUID(), extension);
        var put = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build();
        var signed = presigner.presignPutObject(PutObjectPresignRequest.builder().signatureDuration(signatureDuration).putObjectRequest(put).build());
        return new PresignedUpload(signed.url().toString(), key, contentType, signatureDuration.toSeconds());
    }

    private static void validateImageContentType(String contentType) {
        if (contentType == null || !(contentType.equalsIgnoreCase("image/jpeg") || contentType.equalsIgnoreCase("image/png") || contentType.equalsIgnoreCase("image/webp"))) {
            throw new IllegalArgumentException("Only JPEG, PNG and WebP images are accepted");
        }
    }

    @Override public void close() { presigner.close(); }

    public enum ImageKind {
        POSTER("poster"), BACKDROP("backdrop");
        final String path;
        ImageKind(String path) { this.path = path; }
    }

    public record PresignedUpload(String uploadUrl, String objectKey, String contentType, long expiresInSeconds) {}
}
