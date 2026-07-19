/*
 * Licensed under the Apache License, Version 2.0.
 */
package org.igniterealtime.openfire.plugins.s3fileupload;

record UploadRequest(String filename, long size, String contentType) {
}
