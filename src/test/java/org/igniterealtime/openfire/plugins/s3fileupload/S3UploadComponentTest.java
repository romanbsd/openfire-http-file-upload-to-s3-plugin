package org.igniterealtime.openfire.plugins.s3fileupload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.dom4j.Element;
import org.junit.jupiter.api.Test;
import org.xmpp.packet.IQ;
import org.xmpp.packet.PacketError;

class S3UploadComponentTest {
    @Test
    void routesRequestAndReturnsXep0363Slot() throws Exception {
        final FakeSlotService service = new FakeSlotService();
        final S3UploadComponent component = new S3UploadComponent(configuration(1024), service);
        final IQ request = request("hello world.jpg", "42", "image/jpeg");

        final IQ response = component.handleIQGet(request);

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

    @Test
    void omitsMaximumFileSizeWhenUploadsAreUnlimited() {
        final S3UploadComponent component = new S3UploadComponent(configuration(-1), new FakeSlotService());

        final Element form = component.handleDiscoInfo(discoRequest()).getChildElement().element("x");

        assertEquals(1, form.elements("field").size());
    }

    @Test
    void rejectsUnauthenticatedAndMalformedRequests() {
        final S3UploadComponent component = new S3UploadComponent(configuration(1024), new FakeSlotService());
        final IQ unauthenticated = request("file.txt", "1", null);
        unauthenticated.setFrom((String) null);

        assertError(component.handleSlotRequest(unauthenticated), PacketError.Condition.not_authorized);
        assertError(component.handleSlotRequest(request("", "1", null)), PacketError.Condition.bad_request);
        assertError(component.handleSlotRequest(request("bad\nname", "1", null)), PacketError.Condition.bad_request);
        assertError(component.handleSlotRequest(request("é".repeat(128), "1", null)),
            PacketError.Condition.bad_request);
        assertError(component.handleSlotRequest(request(".", "1", null)), PacketError.Condition.bad_request);
        assertError(component.handleSlotRequest(request("..", "1", null)), PacketError.Condition.bad_request);
        assertError(component.handleSlotRequest(request("file.txt", null, null)), PacketError.Condition.bad_request);
        assertError(component.handleSlotRequest(request("file.txt", "-1", null)), PacketError.Condition.bad_request);
        assertError(component.handleSlotRequest(request("file.txt", "not-a-number", null)),
            PacketError.Condition.bad_request);
        assertError(component.handleSlotRequest(request("file.txt", "1", "text/plain\ninvalid")),
            PacketError.Condition.bad_request);
    }

    @Test
    void acceptsZeroByteFiles() {
        final FakeSlotService service = new FakeSlotService();
        final S3UploadComponent component = new S3UploadComponent(configuration(1024), service);

        final IQ response = component.handleSlotRequest(request("empty.txt", "0", null));

        assertEquals(IQ.Type.result, response.getType());
        assertEquals(0, service.lastRequest.size());
    }

    @Test
    void reconfigureKeepsAFixedServiceOpen() {
        final FakeSlotService service = new FakeSlotService();
        final S3UploadComponent component = new S3UploadComponent(configuration(10), service);

        component.reconfigure(configuration(20));
        final IQ response = component.handleSlotRequest(request("still-works.txt", "1", null));

        assertFalse(service.closed);
        assertEquals(IQ.Type.result, response.getType());
    }

    @Test
    void reportsUnavailableConfigurationAndSlotFailures() {
        final S3UploadConfiguration invalid = new S3UploadConfiguration(
            "", "us-east-1", "", false, true, "", "", "", "upload", 1024,
            Duration.ofMinutes(5), Duration.ofHours(1));
        final S3UploadComponent unavailable = new S3UploadComponent(invalid);
        final FakeSlotService failingService = new FakeSlotService();
        failingService.failure = new IllegalStateException("signing failed");
        final S3UploadComponent failing = new S3UploadComponent(configuration(1024), failingService);

        assertError(unavailable.handleSlotRequest(request("file.txt", "1", null)),
            PacketError.Condition.service_unavailable);
        assertError(failing.handleSlotRequest(request("file.txt", "1", null)),
            PacketError.Condition.internal_server_error);
    }

    @Test
    void reconfigureClosesPreviousServiceAndUsesReplacement() {
        final FakeSlotService first = new FakeSlotService();
        final FakeSlotService second = new FakeSlotService();
        final List<FakeSlotService> services = List.of(first, second);
        final AtomicInteger nextService = new AtomicInteger();
        final S3UploadComponent component = new S3UploadComponent(
            configuration(10), ignored -> services.get(nextService.getAndIncrement()));

        component.handleSlotRequest(request("first.txt", "1", null));
        component.reconfigure(configuration(20));
        component.handleSlotRequest(request("second.txt", "2", null));

        assertTrue(first.closed);
        assertEquals("first.txt", first.lastRequest.filename());
        assertEquals("second.txt", second.lastRequest.filename());
        component.preComponentShutdown();
        assertTrue(second.closed);
        assertError(component.handleSlotRequest(request("after-shutdown.txt", "1", null)),
            PacketError.Condition.service_unavailable);
    }

    @Test
    void reconfigureAfterShutdownClosesReplacementAndStaysDown() {
        final FakeSlotService first = new FakeSlotService();
        final FakeSlotService second = new FakeSlotService();
        final List<FakeSlotService> services = List.of(first, second);
        final AtomicInteger nextService = new AtomicInteger();
        final S3UploadComponent component = new S3UploadComponent(
            configuration(10), ignored -> services.get(nextService.getAndIncrement()));

        component.preComponentShutdown();
        component.reconfigure(configuration(20));

        assertTrue(first.closed);
        assertTrue(second.closed);
        assertError(component.handleSlotRequest(request("late.txt", "1", null)),
            PacketError.Condition.service_unavailable);
    }

    @Test
    void reconfigureSurvivesAFailingCloseOfThePreviousService() {
        final FakeSlotService first = new FakeSlotService();
        first.closeFailure = new IllegalStateException("close failed");
        final FakeSlotService second = new FakeSlotService();
        final List<FakeSlotService> services = List.of(first, second);
        final AtomicInteger nextService = new AtomicInteger();
        final S3UploadComponent component = new S3UploadComponent(
            configuration(10), ignored -> services.get(nextService.getAndIncrement()));

        component.reconfigure(configuration(20));
        final IQ response = component.handleSlotRequest(request("still-works.txt", "1", null));

        assertTrue(first.closed);
        assertEquals(IQ.Type.result, response.getType());
        assertEquals("still-works.txt", second.lastRequest.filename());
    }

    @Test
    void reconfigureWaitsForInFlightPresignBeforeClosingService() throws Exception {
        final CountDownLatch signingStarted = new CountDownLatch(1);
        final CountDownLatch releaseSigning = new CountDownLatch(1);
        final CountDownLatch replacementCreated = new CountDownLatch(1);
        final FakeSlotService firstDelegate = new FakeSlotService();
        final UploadSlotService first = new BlockingSlotService(firstDelegate, signingStarted, releaseSigning);
        final FakeSlotService second = new FakeSlotService();
        final List<UploadSlotService> services = List.of(first, second);
        final AtomicInteger nextService = new AtomicInteger();
        final S3UploadComponent component = new S3UploadComponent(configuration(10), ignored -> {
            final int index = nextService.getAndIncrement();
            if (index > 0) {
                replacementCreated.countDown();
            }
            return services.get(index);
        });
        final ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            final Future<IQ> upload = executor.submit(
                () -> component.handleSlotRequest(request("in-flight.txt", "1", null)));
            assertTrue(signingStarted.await(5, TimeUnit.SECONDS));

            final Future<?> reload = executor.submit(() -> component.reconfigure(configuration(20)));
            assertTrue(replacementCreated.await(5, TimeUnit.SECONDS));
            assertFalse(reload.isDone());

            releaseSigning.countDown();
            assertEquals(IQ.Type.result, upload.get(5, TimeUnit.SECONDS).getType());
            reload.get(5, TimeUnit.SECONDS);
            assertTrue(firstDelegate.closed);
        } finally {
            releaseSigning.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static IQ request(String filename, String size, String contentType) {
        final IQ iq = new IQ(IQ.Type.get, "slot-1");
        iq.setFrom("romeo@example.test/garden");
        iq.setTo("upload.example.test");
        final Element request = iq.setChildElement("request", S3UploadComponent.UPLOAD_NAMESPACE);
        if (filename != null) {
            request.addAttribute("filename", filename);
        }
        if (size != null) {
            request.addAttribute("size", size);
        }
        if (contentType != null) {
            request.addAttribute("content-type", contentType);
        }
        return iq;
    }

    private static IQ discoRequest() {
        final IQ request = new IQ(IQ.Type.get);
        request.setFrom("romeo@example.test/garden");
        request.setTo("upload.example.test");
        request.setChildElement("query", S3UploadComponent.NAMESPACE_DISCO_INFO);
        return request;
    }

    private static void assertError(IQ response, PacketError.Condition condition) {
        assertEquals(IQ.Type.error, response.getType());
        assertEquals(condition, response.getError().getCondition());
    }

    private static S3UploadConfiguration configuration(long maxSize) {
        return new S3UploadConfiguration("files", "us-east-1", "", false, true, "", "", "xmpp", "upload",
            maxSize, Duration.ofMinutes(5), Duration.ofDays(7));
    }

    private static final class FakeSlotService implements UploadSlotService {
        private UploadRequest lastRequest;
        private RuntimeException failure;
        private RuntimeException closeFailure;
        private boolean closed;

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

    private record BlockingSlotService(
        FakeSlotService delegate,
        CountDownLatch signingStarted,
        CountDownLatch releaseSigning
    ) implements UploadSlotService {
        @Override
        public UploadSlot createSlot(UploadRequest request) {
            signingStarted.countDown();
            try {
                if (!releaseSigning.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to finish signing");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Signing interrupted", e);
            }
            return delegate.createSlot(request);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
