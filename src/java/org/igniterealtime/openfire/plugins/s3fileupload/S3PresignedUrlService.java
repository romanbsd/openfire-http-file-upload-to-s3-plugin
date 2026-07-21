/*
 * Licensed under the Apache License, Version 2.0.
 */
package org.igniterealtime.openfire.plugins.s3fileupload;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.utils.http.SdkHttpUtils;

final class S3PresignedUrlService implements UploadSlotService {
    private static final Pattern NON_PRINTABLE_ASCII = Pattern.compile("[^\\x20-\\x7E]");

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
        final String contentType = request.contentType();
        final boolean hasContentType = contentType != null && !contentType.isBlank();

        final PutObjectRequest.Builder putObject = PutObjectRequest.builder()
            .bucket(configuration.bucket())
            .key(key)
            .contentLength(request.size());
        final GetObjectRequest.Builder getObject = GetObjectRequest.builder()
            .bucket(configuration.bucket())
            .key(key)
            .responseContentDisposition(contentDisposition(request.filename()));
        if (hasContentType) {
            putObject.contentType(contentType);
            getObject.responseContentType(contentType);
        }

        final String putUrl = presigner.presignPutObject(PutObjectPresignRequest.builder()
            .signatureDuration(configuration.putExpiration())
            .putObjectRequest(putObject.build())
            .build()).url().toExternalForm();

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
            .credentialsProvider(credentialsProvider(configuration))
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(configuration.pathStyleAccess())
                .build());
        final URI endpoint = configuration.endpointUri();
        if (endpoint != null) {
            builder.endpointOverride(endpoint);
        }
        return builder.build();
    }

    private static AwsCredentialsProvider credentialsProvider(S3UploadConfiguration configuration) {
        if (configuration.useDefaultAwsCredentials()) {
            return DefaultCredentialsProvider.builder().build();
        }
        return StaticCredentialsProvider.create(
            AwsBasicCredentials.create(configuration.accessKey(), configuration.secretKey()));
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
        final String asciiFallback = NON_PRINTABLE_ASCII.matcher(filename).replaceAll("_")
            .replace("\\", "_").replace("\"", "_");
        return "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''"
            + SdkHttpUtils.urlEncode(filename);
    }
}
