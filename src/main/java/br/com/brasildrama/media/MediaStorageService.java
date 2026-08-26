package br.com.brasildrama.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

@Service
public class MediaStorageService implements AutoCloseable {
    private final String bucket;
    private final Duration uploadSignatureDuration;
    private final Duration readSignatureDuration;
    private final long maxImageBytes;
    private final S3Presigner presigner;
    private final S3Client s3;

    public MediaStorageService(
        @Value("${brasil-drama.media.bucket}") String bucket,
        @Value("${brasil-drama.media.region}") String region,
        @Value("${brasil-drama.media.presign-minutes:15}") long presignMinutes,
        @Value("${brasil-drama.media.read-presign-minutes:60}") long readPresignMinutes,
        @Value("${brasil-drama.media.max-image-bytes:15728640}") long maxImageBytes
    ) {
        this.bucket = bucket;
        this.uploadSignatureDuration = Duration.ofMinutes(presignMinutes);
        this.readSignatureDuration = Duration.ofMinutes(readPresignMinutes);
        this.maxImageBytes = maxImageBytes;
        var awsRegion = Region.of(region);
        this.presigner = S3Presigner.builder().region(awsRegion).build();
        this.s3 = S3Client.builder().region(awsRegion).build();
    }

    public PresignedUpload presignDramaImage(UUID dramaId, ImageKind kind, String contentType) {
        validateImageContentType(contentType);
        var normalizedContentType = contentType.toLowerCase(Locale.ROOT);
        var extension = switch (normalizedContentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/jpeg" -> "jpg";
            default -> throw new IllegalArgumentException("Unsupported image type");
        };
        var key = "dramas/%s/%s/%s.%s".formatted(dramaId, kind.path, UUID.randomUUID(), extension);
        var put = PutObjectRequest.builder().bucket(bucket).key(key).contentType(normalizedContentType).build();
        var signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
            .signatureDuration(uploadSignatureDuration).putObjectRequest(put).build());
        return new PresignedUpload(signed.url().toString(), key, normalizedContentType, uploadSignatureDuration.toSeconds());
    }

    public StoredImage verifyDramaImage(UUID dramaId, ImageKind kind, String objectKey) {
        validateObjectKey(dramaId, kind, objectKey);
        var head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
        validateImageContentType(head.contentType());
        if (head.contentLength() == null || head.contentLength() <= 0 || head.contentLength() > maxImageBytes) {
            throw new IllegalArgumentException("Uploaded image size is invalid");
        }
        return new StoredImage(objectKey, head.contentType(), head.contentLength(), readUrl(objectKey));
    }

    public String readUrl(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return null;
        var get = GetObjectRequest.builder().bucket(bucket).key(objectKey).build();
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
            .signatureDuration(readSignatureDuration).getObjectRequest(get).build()).url().toString();
    }

    private static void validateObjectKey(UUID dramaId, ImageKind kind, String objectKey) {
        var prefix = "dramas/%s/%s/".formatted(dramaId, kind.path);
        if (objectKey == null || objectKey.isBlank() || !objectKey.startsWith(prefix) || objectKey.contains("..")) {
            throw new IllegalArgumentException("Object key does not belong to this drama and image kind");
        }
    }

    private static void validateImageContentType(String contentType) {
        if (contentType == null || !(contentType.equalsIgnoreCase("image/jpeg") || contentType.equalsIgnoreCase("image/png") || contentType.equalsIgnoreCase("image/webp"))) {
            throw new IllegalArgumentException("Only JPEG, PNG and WebP images are accepted");
        }
    }

    @Override public void close() { presigner.close(); s3.close(); }

    public enum ImageKind {
        POSTER("poster"), BACKDROP("backdrop");
        final String path;
        ImageKind(String path) { this.path = path; }
    }

    public record PresignedUpload(String uploadUrl, String objectKey, String contentType, long expiresInSeconds) {}
    public record StoredImage(String objectKey, String contentType, long contentLength, String previewUrl) {}
}
