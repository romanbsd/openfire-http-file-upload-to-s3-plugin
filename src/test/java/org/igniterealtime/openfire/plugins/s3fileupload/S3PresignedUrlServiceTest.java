package org.igniterealtime.openfire.plugins.s3fileupload;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3PresignedUrlServiceTest {
    @Test
    void signsPutAndGetWithoutNetworkAccess() {
        final S3UploadConfiguration configuration = new S3UploadConfiguration(
            "files", "us-east-1", "https://s3.example.test", true, "xmpp", "upload",
            1024, Duration.ofMinutes(5), Duration.ofHours(1));
        final S3Presigner presigner = S3Presigner.builder()
            .endpointOverride(configuration.endpointUri())
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("access", "secret")))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true)
                .checksumValidationEnabled(false).build())
            .build();
        final S3ObjectKeyFactory keys = new S3ObjectKeyFactory("xmpp",
            () -> UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));

        try (S3PresignedUrlService service = new S3PresignedUrlService(configuration, presigner, keys)) {
            final UploadSlot slot = service.createSlot(
                new UploadRequest("été photo.jpg", 42, "image/jpeg"));

            assertTrue(slot.putUrl().startsWith("https://s3.example.test/files/xmpp/123e4567-e89b-12d3-a456-426614174000/"));
            assertTrue(slot.putUrl().contains("X-Amz-Expires=300"));
            assertTrue(slot.putUrl().contains("X-Amz-SignedHeaders=content-length%3Bcontent-type%3Bhost"));
            assertTrue(slot.getUrl().contains("X-Amz-Expires=3600"));
            assertTrue(slot.getUrl().contains("response-content-disposition="));
        }
    }
}
