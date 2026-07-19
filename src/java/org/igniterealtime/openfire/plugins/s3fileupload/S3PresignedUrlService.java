/*
 * Licensed under the Apache License, Version 2.0.
 */
package org.igniterealtime.openfire.plugins.s3fileupload;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

final class S3PresignedUrlService implements UploadSlotService {
    private final S3UploadConfiguration configuration;
    private final S3ObjectKeyFactory keyFactory;
    private final S3Presigner presigner;

    S3PresignedUrlService(S3UploadConfiguration configuration) {
        this(configuration, createPresigner(requireValid(configuration)),
            new S3ObjectKeyFactory(configuration.keyPrefix()));
    }

    S3PresignedUrlService(
        S3UploadConfiguration configuration,
        S3Presigner presigner,
        S3ObjectKeyFactory keyFactory
    ) {
        this.configuration = requireValid(configuration);
        this.presigner = Objects.requireNonNull(presigner);
        this.keyFactory = Objects.requireNonNull(keyFactory);
    }

    @Override
    public UploadSlot createSlot(UploadRequest request) {
        final String key = keyFactory.create(request.filename());
        final boolean hasContentType = request.contentType() != null && !request.contentType().isBlank();

        final PutObjectRequest.Builder putObject = PutObjectRequest.builder()
            .bucket(configuration.bucket())
            .key(key)
            .contentLength(request.size());
        if (hasContentType) {
            putObject.contentType(request.contentType());
        }

        final String putUrl = presigner.presignPutObject(PutObjectPresignRequest.builder()
            .signatureDuration(configuration.putExpiration())
            .putObjectRequest(putObject.build())
            .build()).url().toExternalForm();

        final GetObjectRequest.Builder getObject = GetObjectRequest.builder()
            .bucket(configuration.bucket())
            .key(key)
            .responseContentDisposition(contentDisposition(request.filename()));
        if (hasContentType) {
            getObject.responseContentType(request.contentType());
        }

        final String getUrl = presigner.presignGetObject(GetObjectPresignRequest.builder()
            .signatureDuration(configuration.getExpiration())
            .getObjectRequest(getObject.build())
            .build()).url().toExternalForm();

        return new UploadSlot(putUrl, getUrl);
    }

    @Override
    public void close() {
        presigner.close();
    }

    private static S3Presigner createPresigner(S3UploadConfiguration configuration) {
        final S3Presigner.Builder builder = S3Presigner.builder()
            .region(Region.of(configuration.region()))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(configuration.pathStyleAccess())
                .checksumValidationEnabled(false)
                .build());
        if (configuration.endpointUri() != null) {
            builder.endpointOverride(configuration.endpointUri());
        }
        return builder.build();
    }

    private static S3UploadConfiguration requireValid(S3UploadConfiguration configuration) {
        Objects.requireNonNull(configuration);
        final List<String> errors = configuration.validationErrors();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        return configuration;
    }

    private static String contentDisposition(String filename) {
        final String asciiFallback = filename.replaceAll("[^\\x20-\\x7E]", "_").replace("\\", "_").replace("\"", "_");
        return "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + rfc3986(filename);
    }

    private static String rfc3986(String value) {
        final StringBuilder result = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            final int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~') {
                result.append((char) c);
            } else {
                result.append('%');
                result.append(Character.toUpperCase(Character.forDigit((c >>> 4) & 0xf, 16)));
                result.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return result.toString();
    }
}
