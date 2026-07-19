/*
 * Licensed under the Apache License, Version 2.0.
 */
package org.igniterealtime.openfire.plugins.s3fileupload;

interface UploadSlotService extends AutoCloseable {
    UploadSlot createSlot(UploadRequest request);

    @Override
    void close();
}
