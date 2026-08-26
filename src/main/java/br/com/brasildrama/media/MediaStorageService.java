package br.com.brasildrama.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.*;

import java.time.Duration;
import java.util.*;

@Service
public class MediaStorageService implements AutoCloseable {
    private final String bucket;
    private final Duration uploadSignatureDuration;
    private final Duration readSignatureDuration;
    private final long maxImageBytes;
    private final long videoPartBytes;
    private final long maxVideoBytes;
    private final S3Presigner presigner;
    private final S3Client s3;

    public MediaStorageService(
        @Value("${brasil-drama.media.bucket}") String bucket,
        @Value("${brasil-drama.media.region}") String region,
        @Value("${brasil-drama.media.presign-minutes:15}") long presignMinutes,
        @Value("${brasil-drama.media.read-presign-minutes:60}") long readPresignMinutes,
        @Value("${brasil-drama.media.max-image-bytes:15728640}") long maxImageBytes,
        @Value("${brasil-drama.media.video-part-bytes:10485760}") long videoPartBytes,
        @Value("${brasil-drama.media.max-video-bytes:2147483648}") long maxVideoBytes
    ) {
        this.bucket = bucket;
        this.uploadSignatureDuration = Duration.ofMinutes(presignMinutes);
        this.readSignatureDuration = Duration.ofMinutes(readPresignMinutes);
        this.maxImageBytes = maxImageBytes;
        this.videoPartBytes = Math.max(5L * 1024 * 1024, videoPartBytes);
        this.maxVideoBytes = maxVideoBytes;
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
        var signed = presigner.presignPutObject(PutObjectPresignRequest.builder().signatureDuration(uploadSignatureDuration).putObjectRequest(put).build());
        return new PresignedUpload(signed.url().toString(), key, normalizedContentType, uploadSignatureDuration.toSeconds());
    }

    public StoredImage verifyDramaImage(UUID dramaId, ImageKind kind, String objectKey) {
        validateImageObjectKey(dramaId, kind, objectKey);
        var head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
        validateImageContentType(head.contentType());
        if (head.contentLength() == null || head.contentLength() <= 0 || head.contentLength() > maxImageBytes) throw new IllegalArgumentException("Uploaded image size is invalid");
        return new StoredImage(objectKey, head.contentType(), head.contentLength(), readUrl(objectKey));
    }

    public MultipartUpload startEpisodeVideo(UUID dramaId, UUID episodeId, String contentType, long fileSize) {
        if (!"video/mp4".equalsIgnoreCase(contentType)) throw new IllegalArgumentException("Only MP4 video is accepted");
        if (fileSize <= 0 || fileSize > maxVideoBytes) throw new IllegalArgumentException("Video size is invalid");
        var partCount = (int) Math.ceil((double) fileSize / videoPartBytes);
        if (partCount > 1000) throw new IllegalArgumentException("Video requires too many parts");
        var objectKey = "dramas/%s/episodes/%s/video/%s.mp4".formatted(dramaId, episodeId, UUID.randomUUID());
        var create = s3.createMultipartUpload(CreateMultipartUploadRequest.builder().bucket(bucket).key(objectKey).contentType("video/mp4").build());
        var parts = new ArrayList<PresignedPart>();
        for (int number = 1; number <= partCount; number++) {
            var request = UploadPartRequest.builder().bucket(bucket).key(objectKey).uploadId(create.uploadId()).partNumber(number).build();
            var signed = presigner.presignUploadPart(UploadPartPresignRequest.builder().signatureDuration(uploadSignatureDuration).uploadPartRequest(request).build());
            parts.add(new PresignedPart(number, signed.url().toString()));
        }
        return new MultipartUpload(create.uploadId(), objectKey, videoPartBytes, fileSize, parts, uploadSignatureDuration.toSeconds());
    }

    public StoredVideo completeEpisodeVideo(UUID dramaId, UUID episodeId, String uploadId, String objectKey, List<UploadedPart> parts) {
        validateVideoObjectKey(dramaId, episodeId, objectKey);
        if (uploadId == null || uploadId.isBlank() || parts == null || parts.isEmpty()) throw new IllegalArgumentException("Multipart upload data is incomplete");
        var completed = parts.stream().sorted(Comparator.comparingInt(UploadedPart::partNumber))
            .map(p -> CompletedPart.builder().partNumber(p.partNumber()).eTag(p.eTag()).build()).toList();
        s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder().bucket(bucket).key(objectKey).uploadId(uploadId)
            .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build()).build());
        var head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
        if (head.contentLength() == null || head.contentLength() <= 0 || head.contentLength() > maxVideoBytes) throw new IllegalArgumentException("Uploaded video size is invalid");
        return new StoredVideo(objectKey, head.contentType(), head.contentLength(), readUrl(objectKey));
    }

    public void abortEpisodeVideo(UUID dramaId, UUID episodeId, String uploadId, String objectKey) {
        validateVideoObjectKey(dramaId, episodeId, objectKey);
        s3.abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(bucket).key(objectKey).uploadId(uploadId).build());
    }

    public String readUrl(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return null;
        var get = GetObjectRequest.builder().bucket(bucket).key(objectKey).build();
        return presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(readSignatureDuration).getObjectRequest(get).build()).url().toString();
    }

    private static void validateImageObjectKey(UUID dramaId, ImageKind kind, String objectKey) {
        validateOwnedKey("dramas/%s/%s/".formatted(dramaId, kind.path), objectKey);
    }

    private static void validateVideoObjectKey(UUID dramaId, UUID episodeId, String objectKey) {
        validateOwnedKey("dramas/%s/episodes/%s/video/".formatted(dramaId, episodeId), objectKey);
    }

    private static void validateOwnedKey(String prefix, String objectKey) {
        if (objectKey == null || objectKey.isBlank() || !objectKey.startsWith(prefix) || objectKey.contains("..")) throw new IllegalArgumentException("Object key does not belong to this content");
    }

    private static void validateImageContentType(String contentType) {
        if (contentType == null || !(contentType.equalsIgnoreCase("image/jpeg") || contentType.equalsIgnoreCase("image/png") || contentType.equalsIgnoreCase("image/webp"))) throw new IllegalArgumentException("Only JPEG, PNG and WebP images are accepted");
    }

    @Override public void close() { presigner.close(); s3.close(); }

    public enum ImageKind {
        POSTER("poster"), BACKDROP("backdrop");
        final String path;
        ImageKind(String path) { this.path = path; }
    }

    public record PresignedUpload(String uploadUrl, String objectKey, String contentType, long expiresInSeconds) {}
    public record StoredImage(String objectKey, String contentType, long contentLength, String previewUrl) {}
    public record PresignedPart(int partNumber, String uploadUrl) {}
    public record MultipartUpload(String uploadId, String objectKey, long partSize, long fileSize, List<PresignedPart> parts, long expiresInSeconds) {}
    public record UploadedPart(int partNumber, String eTag) {}
    public record StoredVideo(String objectKey, String contentType, long contentLength, String playbackUrl) {}
}
