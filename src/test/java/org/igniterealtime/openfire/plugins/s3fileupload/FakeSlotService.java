package org.igniterealtime.openfire.plugins.s3fileupload;

final class FakeSlotService implements UploadSlotService {
    UploadRequest lastRequest;
    RuntimeException failure;
    RuntimeException closeFailure;
    boolean closed;

    @Override
    public UploadSlot createSlot(UploadRequest request) {
        if (failure != null) {
            throw failure;
        }
        lastRequest = request;
        return new UploadSlot("https://s3.test/put?a=1&b=2", "https://s3.test/get?a=1&b=2");
    }

    @Override
    public void close() {
        closed = true;
        if (closeFailure != null) {
            throw closeFailure;
        }
    }
}
