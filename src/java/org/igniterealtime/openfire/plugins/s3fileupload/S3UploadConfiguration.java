/*
 * Licensed under the Apache License, Version 2.0.
 */
package org.igniterealtime.openfire.plugins.s3fileupload;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable, validated runtime configuration for the S3 upload service. */
public record S3UploadConfiguration(
    String bucket,
    String region,
    String endpoint,
    boolean pathStyleAccess,
    String keyPrefix,
    String serviceSubdomain,
    long maxFileSize,
    Duration putExpiration,
    Duration getExpiration
) {
    static final int MAX_FILENAME_BYTES = 255;
    static final Duration MAX_PRESIGN_DURATION = Duration.ofDays(7);
    private static final Pattern SUBDOMAIN =
        Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");

    public S3UploadConfiguration {
        bucket = trim(bucket);
        region = trim(region);
        endpoint = trim(endpoint);
        keyPrefix = normalizePrefix(keyPrefix);
        serviceSubdomain = trim(serviceSubdomain);
        Objects.requireNonNull(putExpiration, "putExpiration");
        Objects.requireNonNull(getExpiration, "getExpiration");
    }

    public boolean isReady() {
        return validationErrors().isEmpty();
    }

    public List<String> validationErrors() {
        final List<String> errors = new ArrayList<>();
        if (bucket.isEmpty()) {
            errors.add("S3 bucket is required");
        }
        if (region.isEmpty()) {
            errors.add("AWS region is required");
        }
        if (!hasValidServiceSubdomain()) {
            errors.add("Service subdomain is invalid");
        }
        if (maxFileSize == 0 || maxFileSize < -1) {
            errors.add("Maximum file size must be -1 or a positive number");
        }
        validateDuration("PUT URL expiration", putExpiration, errors);
        validateDuration("GET URL expiration", getExpiration, errors);
        validateEndpoint(errors);
        return List.copyOf(errors);
    }

    public URI endpointUri() {
        return endpoint.isEmpty() ? null : URI.create(endpoint);
    }

    boolean hasValidServiceSubdomain() {
        return SUBDOMAIN.matcher(serviceSubdomain).matches();
    }

    private void validateEndpoint(List<String> errors) {
        if (endpoint.isEmpty()) {
            return;
        }
        try {
            final URI uri = new URI(endpoint);
            if (!uri.isAbsolute() || uri.getHost() == null) {
                errors.add("S3 endpoint must be an absolute HTTP(S) URL");
            } else if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                errors.add("S3 endpoint must use HTTP or HTTPS");
            }
        } catch (URISyntaxException e) {
            errors.add("S3 endpoint is not a valid URI");
        }
    }

    private static void validateDuration(String name, Duration duration, List<String> errors) {
        if (duration.isZero() || duration.isNegative() || duration.compareTo(MAX_PRESIGN_DURATION) > 0) {
            errors.add(name + " must be between 1 second and 7 days");
        }
    }

    private static String normalizePrefix(String value) {
        String result = trim(value).replace('\\', '/');
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
