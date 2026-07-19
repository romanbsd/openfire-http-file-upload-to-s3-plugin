package org.igniterealtime.openfire.plugins.s3fileupload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class S3UploadConfigurationTest {
    @Test
    void acceptsAwsAndNormalizesPrefix() {
        final S3UploadConfiguration configuration = new S3UploadConfiguration(
            " files ", " eu-central-1 ", "", false, "/chat/uploads/", "upload",
            1024, Duration.ofMinutes(5), Duration.ofDays(7));

        assertTrue(configuration.isReady());
        assertEquals("files", configuration.bucket());
        assertEquals("chat/uploads", configuration.keyPrefix());
    }

    @Test
    void acceptsS3CompatibleEndpoint() {
        final S3UploadConfiguration configuration = new S3UploadConfiguration(
            "files", "local", "https://minio.example.test:9000", true, "", "upload",
            -1, Duration.ofMinutes(5), Duration.ofHours(1));

        assertTrue(configuration.isReady());
        assertEquals("minio.example.test", configuration.endpointUri().getHost());
    }

    @Test
    void rejectsMissingBucketInvalidLimitAndOverlongSignature() {
        final S3UploadConfiguration configuration = new S3UploadConfiguration(
            "", "us-east-1", "ftp://s3.example.test", false, "files", "-bad",
            0, Duration.ZERO, Duration.ofDays(8));

        assertFalse(configuration.isReady());
        assertEquals(6, configuration.validationErrors().size());
    }
}
