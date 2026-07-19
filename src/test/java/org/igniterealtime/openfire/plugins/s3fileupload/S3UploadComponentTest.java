package org.igniterealtime.openfire.plugins.s3fileupload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;

import org.dom4j.Element;
import org.junit.jupiter.api.Test;
import org.xmpp.packet.IQ;
import org.xmpp.packet.PacketError;

class S3UploadComponentTest {
    @Test
    void returnsXep0363Slot() {
        final FakeSlotService service = new FakeSlotService();
        final S3UploadComponent component = new S3UploadComponent(configuration(1024), service);
        final IQ request = request("hello world.jpg", "42", "image/jpeg");

        final IQ response = component.handleSlotRequest(request);

        assertEquals(IQ.Type.result, response.getType());
        assertEquals(S3UploadComponent.UPLOAD_NAMESPACE, response.getChildElement().getNamespaceURI());
        assertEquals("https://s3.test/put?a=1&b=2",
            response.getChildElement().element("put").attributeValue("url"));
        assertEquals("https://s3.test/get?a=1&b=2",
            response.getChildElement().element("get").attributeValue("url"));
        assertEquals("hello world.jpg", service.lastRequest.filename());
        assertEquals(42, service.lastRequest.size());
    }

    @Test
    void returnsFileTooLargeApplicationError() {
        final S3UploadComponent component = new S3UploadComponent(configuration(10), new FakeSlotService());

        final IQ response = component.handleSlotRequest(request("large.bin", "11", null));

        assertEquals(IQ.Type.error, response.getType());
        assertEquals(PacketError.Condition.not_acceptable, response.getError().getCondition());
        final Element tooLarge = response.getError().getElement().element("file-too-large");
        assertNotNull(tooLarge);
        assertEquals(S3UploadComponent.UPLOAD_NAMESPACE, tooLarge.getNamespaceURI());
        assertEquals("10", tooLarge.elementText("max-file-size"));
    }

    @Test
    void advertisesMaximumFileSizeInDiscoForm() {
        final S3UploadComponent component = new S3UploadComponent(configuration(4096), new FakeSlotService());
        final IQ request = new IQ(IQ.Type.get);
        request.setFrom("romeo@example.test/garden");
        request.setTo("upload.example.test");
        request.setChildElement("query", S3UploadComponent.NAMESPACE_DISCO_INFO);

        final IQ response = component.handleDiscoInfo(request);

        final Element form = response.getChildElement().element("x");
        assertNotNull(form);
        assertEquals("jabber:x:data", form.getNamespaceURI());
        assertEquals("4096", form.elements("field").get(1).elementText("value"));
    }

    private static IQ request(String filename, String size, String contentType) {
        final IQ iq = new IQ(IQ.Type.get, "slot-1");
        iq.setFrom("romeo@example.test/garden");
        iq.setTo("upload.example.test");
        final Element request = iq.setChildElement("request", S3UploadComponent.UPLOAD_NAMESPACE);
        request.addAttribute("filename", filename);
        request.addAttribute("size", size);
        if (contentType != null) {
            request.addAttribute("content-type", contentType);
        }
        return iq;
    }

    private static S3UploadConfiguration configuration(long maxSize) {
        return new S3UploadConfiguration("files", "us-east-1", "", false, "xmpp", "upload",
            maxSize, Duration.ofMinutes(5), Duration.ofDays(7));
    }

    private static final class FakeSlotService implements UploadSlotService {
        private UploadRequest lastRequest;

        @Override
        public UploadSlot createSlot(UploadRequest request) {
            lastRequest = request;
            return new UploadSlot("https://s3.test/put?a=1&b=2", "https://s3.test/get?a=1&b=2");
        }

        @Override
        public void close() {
        }
    }
}
