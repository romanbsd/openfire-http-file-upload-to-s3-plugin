/*
 * Licensed under the Apache License, Version 2.0.
 */
package org.igniterealtime.openfire.plugins.s3fileupload;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

final class S3ObjectKeyFactory {
    private final String prefix;
    private final Supplier<UUID> uuidSupplier;

    S3ObjectKeyFactory(String prefix) {
        this(prefix, UUID::randomUUID);
    }

    S3ObjectKeyFactory(String prefix, Supplier<UUID> uuidSupplier) {
        this.prefix = prefix;
        this.uuidSupplier = Objects.requireNonNull(uuidSupplier);
    }

    String create(String filename) {
        final String safeFilename = filename
            .replace('\\', '_')
            .replace('/', '_')
            .replace("\u0000", "_");
        final String objectName = uuidSupplier.get() + "/" + safeFilename;
        return prefix == null || prefix.isBlank() ? objectName : prefix + "/" + objectName;
    }
}
