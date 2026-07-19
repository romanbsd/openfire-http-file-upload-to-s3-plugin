package org.igniterealtime.openfire.plugins.s3fileupload;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class S3ObjectKeyFactoryTest {
    @Test
    void preservesFilenameButRemovesPathSeparators() {
        final UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        final S3ObjectKeyFactory factory = new S3ObjectKeyFactory("xmpp/files", () -> uuid);

        assertEquals("xmpp/files/123e4567-e89b-12d3-a456-426614174000/.._photo_été.jpg",
            factory.create("../photo/été.jpg"));
    }

    @Test
    void createsRootKeyAndRemovesBackslashesAndNullBytes() {
        final UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        final S3ObjectKeyFactory factory = new S3ObjectKeyFactory("", () -> uuid);

        assertEquals("123e4567-e89b-12d3-a456-426614174000/folder_photo_.jpg",
            factory.create("folder\\photo\0.jpg"));
    }
}
