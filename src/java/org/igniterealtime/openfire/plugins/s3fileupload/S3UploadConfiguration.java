/*
 * Licensed under the Apache License, Version 2.0.
 */
package org.igniterealtime.openfire.plugins.s3fileupload;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable, validated runtime configuration for the S3 upload service. */
public final class S3UploadConfiguration {
    static final int MAX_FILENAME_BYTES = 255;
    static final Duration MAX_PRESIGN_DURATION = Duration.ofDays(7);
    private static final String DNS_LABEL = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?";
    private static final Pattern SUBDOMAIN = Pattern.compile(DNS_LABEL + "(?:\\." + DNS_LABEL + ")*");

    private final String bucket;
    private final String region;
    private final String endpoint;
    private final boolean pathStyleAccess;
    private final boolean useDefaultAwsCredentials;
    private final String accessKey;
    private final String secretKey;
    private final String keyPrefix;
    private final String serviceSubdomain;
    private final long maxFileSize;
    private final Duration putExpiration;
    private final Duration getExpiration;
    private final URI endpointUri;
    private final List<String> validationErrors;

    public S3UploadConfiguration(
        String bucket,
        String region,
        String endpoint,
        boolean pathStyleAccess,
        boolean useDefaultAwsCredentials,
        String accessKey,
        String secretKey,
        String keyPrefix,
        String serviceSubdomain,
        long maxFileSize,
        Duration putExpiration,
        Duration getExpiration
    ) {
        this.bucket = trim(bucket);
        this.region = trim(region);
        this.endpoint = trim(endpoint);
        this.pathStyleAccess = pathStyleAccess;
        this.useDefaultAwsCredentials = useDefaultAwsCredentials;
        this.accessKey = trim(accessKey);
        this.secretKey = secretKey == null ? "" : secretKey;
        this.keyPrefix = normalizePrefix(keyPrefix);
        this.serviceSubdomain = trim(serviceSubdomain);
        this.maxFileSize = maxFileSize;
        this.putExpiration = Objects.requireNonNull(putExpiration, "putExpiration");
        this.getExpiration = Objects.requireNonNull(getExpiration, "getExpiration");

        final List<String> errors = new ArrayList<>();
        final List<String> endpointErrors = new ArrayList<>();
        endpointUri = parseEndpoint(endpointErrors);
        validate(errors);
        errors.addAll(endpointErrors);
        validationErrors = List.copyOf(errors);
    }

    public boolean isReady() {
        return validationErrors.isEmpty();
    }

    public List<String> validationErrors() {
        return validationErrors;
    }

    public String bucket() {
        return bucket;
    }

    public String region() {
        return region;
    }

    public String endpoint() {
        return endpoint;
    }

    public boolean pathStyleAccess() {
        return pathStyleAccess;
    }

    public boolean useDefaultAwsCredentials() {
        return useDefaultAwsCredentials;
    }

    public String accessKey() {
        return accessKey;
    }

    public String secretKey() {
        return secretKey;
    }

    public String keyPrefix() {
        return keyPrefix;
    }

    public String serviceSubdomain() {
        return serviceSubdomain;
    }

    public long maxFileSize() {
        return maxFileSize;
    }

    public Duration putExpiration() {
        return putExpiration;
    }

    public Duration getExpiration() {
        return getExpiration;
    }

    public URI endpointUri() {
        return endpointUri;
    }

    private void validate(List<String> errors) {
        if (bucket.isEmpty()) {
            errors.add("S3 bucket is required");
        }
        if (region.isEmpty()) {
            errors.add("AWS region is required");
        }
        if (!useDefaultAwsCredentials && accessKey.isEmpty()) {
            errors.add("S3 access key is required when the default AWS credential chain is disabled");
        }
        if (!useDefaultAwsCredentials && secretKey.isEmpty()) {
            errors.add("S3 secret key is required when the default AWS credential chain is disabled");
        }
        if (!hasValidServiceSubdomain()) {
            errors.add("Service subdomain is invalid");
        }
        if (maxFileSize == 0 || maxFileSize < -1) {
            errors.add("Maximum file size must be -1 or a positive number");
        }
        validateDuration("PUT URL expiration", putExpiration, errors);
        validateDuration("GET URL expiration", getExpiration, errors);
    }

    @Override
    public String toString() {
        return ("S3UploadConfiguration[bucket=%s, region=%s, endpoint=%s, pathStyleAccess=%s, "
            + "useDefaultAwsCredentials=%s, credentials=<redacted>, keyPrefix=%s, serviceSubdomain=%s, "
            + "maxFileSize=%d, putExpiration=%s, getExpiration=%s]")
            .formatted(bucket, region, endpoint, pathStyleAccess, useDefaultAwsCredentials, keyPrefix,
                serviceSubdomain, maxFileSize, putExpiration, getExpiration);
    }

    boolean hasValidServiceSubdomain() {
        return serviceSubdomain.length() <= 253 && SUBDOMAIN.matcher(serviceSubdomain).matches();
    }

    private URI parseEndpoint(List<String> errors) {
        if (endpoint.isEmpty()) {
            return null;
        }
        try {
            final URI uri = URI.create(endpoint);
            if (!uri.isAbsolute() || uri.getHost() == null) {
                errors.add("S3 endpoint must be an absolute HTTP(S) URL");
            } else if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                errors.add("S3 endpoint must use HTTP or HTTPS");
            } else if (uri.getRawUserInfo() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
                errors.add("S3 endpoint must contain only a scheme, host, and optional port");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            errors.add("S3 endpoint is not a valid URI");
            return null;
        }
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof S3UploadConfiguration other)) {
            return false;
        }
        return pathStyleAccess == other.pathStyleAccess
            && useDefaultAwsCredentials == other.useDefaultAwsCredentials
            && maxFileSize == other.maxFileSize
            && bucket.equals(other.bucket)
            && region.equals(other.region)
            && endpoint.equals(other.endpoint)
            && accessKey.equals(other.accessKey)
            && secretKey.equals(other.secretKey)
            && keyPrefix.equals(other.keyPrefix)
            && serviceSubdomain.equals(other.serviceSubdomain)
            && putExpiration.equals(other.putExpiration)
            && getExpiration.equals(other.getExpiration);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bucket, region, endpoint, pathStyleAccess, useDefaultAwsCredentials,
            accessKey, secretKey, keyPrefix, serviceSubdomain, maxFileSize, putExpiration, getExpiration);
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
