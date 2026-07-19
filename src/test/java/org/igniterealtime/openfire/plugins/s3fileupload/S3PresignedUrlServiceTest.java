package org.igniterealtime.openfire.plugins.s3fileupload;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        final S3UploadConfiguration configuration = configuration();

        try (S3PresignedUrlService service = service(configuration)) {
            final UploadSlot slot = service.createSlot(
                new UploadRequest("été photo.jpg", 42, "image/jpeg"));

            assertTrue(slot.putUrl().startsWith("https://s3.example.test/files/xmpp/123e4567-e89b-12d3-a456-426614174000/"));
            assertTrue(slot.putUrl().contains("X-Amz-Expires=300"));
            assertTrue(slot.putUrl().contains("X-Amz-SignedHeaders=content-length%3Bcontent-type%3Bhost"));
            assertTrue(slot.getUrl().contains("X-Amz-Expires=3600"));
            assertTrue(slot.getUrl().contains("response-content-disposition="));
        }
    }

    @Test
    void omitsOptionalContentTypeWhenItIsBlank() {
        final S3UploadConfiguration configuration = configuration();

        try (S3PresignedUrlService service = service(configuration)) {
            final UploadSlot slot = service.createSlot(new UploadRequest("notes.txt", 12, " "));

            assertTrue(slot.putUrl().contains("X-Amz-SignedHeaders=content-length%3Bhost"));
            assertFalse(slot.putUrl().contains("content-type"));
            assertFalse(slot.getUrl().contains("response-content-type"));
        }
    }

    @Test
    void validatesConfigurationBeforeCreatingDefaultPresigner() {
        final S3UploadConfiguration invalid = new S3UploadConfiguration(
            "", "", "", false, true, "", "", "", "upload", 1024,
            Duration.ofMinutes(5), Duration.ofHours(1));

        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new S3PresignedUrlService(invalid));

        assertTrue(error.getMessage().contains("S3 bucket is required"));
        assertTrue(error.getMessage().contains("AWS region is required"));
    }

    @Test
    void createsProductionPresignerFromValidConfiguration() {
        try (S3PresignedUrlService ignored = new S3PresignedUrlService(configuration())) {
            // Construction verifies the production AWS SDK wiring without making a network request.
        }
    }

    @Test
    void signsWithConfiguredStaticCredentials() {
        final S3UploadConfiguration configuration = new S3UploadConfiguration(
            "files", "us-east-1", "https://s3.example.test", true,
            false, "configured-access", "configured-secret", "xmpp", "upload",
            1024, Duration.ofMinutes(5), Duration.ofHours(1));

        try (S3PresignedUrlService service = new S3PresignedUrlService(configuration)) {
            final UploadSlot slot = service.createSlot(new UploadRequest("static.txt", 12, "text/plain"));

            assertTrue(slot.putUrl().startsWith("https://s3.example.test/files/"));
            assertTrue(slot.putUrl().contains("X-Amz-Credential=configured-access%2F"));
            assertTrue(slot.getUrl().contains("X-Amz-Credential=configured-access%2F"));
        }
    }

    private static S3UploadConfiguration configuration() {
        return new S3UploadConfiguration(
            "files", "us-east-1", "https://s3.example.test", true, true, "", "", "xmpp", "upload",
            1024, Duration.ofMinutes(5), Duration.ofHours(1));
    }

    private static S3PresignedUrlService service(S3UploadConfiguration configuration) {
        final S3Presigner presigner = S3Presigner.builder()
            .endpointOverride(configuration.endpointUri())
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("access", "secret")))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
        final S3ObjectKeyFactory keys = new S3ObjectKeyFactory("xmpp",
            () -> UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
        return new S3PresignedUrlService(configuration, presigner, keys);
    }
}
