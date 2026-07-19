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
            " files ", " eu-central-1 ", "", false, true, "", "", "/chat/uploads/", "upload",
            1024, Duration.ofMinutes(5), Duration.ofDays(7));

        assertTrue(configuration.isReady());
        assertEquals("files", configuration.bucket());
        assertEquals("chat/uploads", configuration.keyPrefix());
    }

    @Test
    void acceptsS3CompatibleEndpoint() {
        final S3UploadConfiguration configuration = new S3UploadConfiguration(
            "files", "local", "https://minio.example.test:9000", true,
            false, "minio-access", "minio-secret", "", "upload",
            -1, Duration.ofMinutes(5), Duration.ofHours(1));

        assertTrue(configuration.isReady());
        assertEquals("minio.example.test", configuration.endpointUri().getHost());
        assertFalse(configuration.toString().contains("minio-secret"));
    }

    @Test
    void requiresBothStaticCredentialValues() {
        final S3UploadConfiguration configuration = new S3UploadConfiguration(
            "files", "local", "https://minio.example.test:9000", true,
            false, "", "", "", "upload",
            1024, Duration.ofMinutes(5), Duration.ofHours(1));

        assertFalse(configuration.isReady());
        assertTrue(configuration.validationErrors().contains(
            "S3 access key is required when the default AWS credential chain is disabled"));
        assertTrue(configuration.validationErrors().contains(
            "S3 secret key is required when the default AWS credential chain is disabled"));
    }

    @Test
    void rejectsMissingBucketInvalidLimitAndOverlongSignature() {
        final S3UploadConfiguration configuration = new S3UploadConfiguration(
            "", "us-east-1", "ftp://s3.example.test", false, true, "", "", "files", "-bad",
            0, Duration.ZERO, Duration.ofDays(8));

        assertFalse(configuration.isReady());
        assertEquals(6, configuration.validationErrors().size());
    }

    @Test
    void requiresServiceSubdomainToBeDnsLabels() {
        for (String subdomain : new String[] {
            "upload-", "_upload", "a".repeat(64), "upload..files", ".upload", "upload.",
            ("a".repeat(63) + ".").repeat(4) + "toolong"}) {
            final S3UploadConfiguration configuration = new S3UploadConfiguration(
                "files", "us-east-1", "", false, true, "", "", "files", subdomain,
                1024, Duration.ofMinutes(5), Duration.ofHours(1));

            assertFalse(configuration.hasValidServiceSubdomain(), subdomain);
        }
        for (String subdomain : new String[] {"upload-2", "upload.files", "a.b.c"}) {
            final S3UploadConfiguration configuration = new S3UploadConfiguration(
                "files", "us-east-1", "", false, true, "", "", "files", subdomain,
                1024, Duration.ofMinutes(5), Duration.ofHours(1));

            assertTrue(configuration.hasValidServiceSubdomain(), subdomain);
        }
    }

    @Test
    void rejectsMalformedAndRelativeEndpoints() {
        final S3UploadConfiguration malformed = new S3UploadConfiguration(
            "files", "us-east-1", "https://bad host", false, true, "", "", "files", "upload",
            1024, Duration.ofMinutes(5), Duration.ofHours(1));
        final S3UploadConfiguration relative = new S3UploadConfiguration(
            "files", "us-east-1", "/s3", false, true, "", "", "files", "upload",
            1024, Duration.ofMinutes(5), Duration.ofHours(1));

        assertFalse(malformed.isReady());
        assertFalse(relative.isReady());
    }
}
