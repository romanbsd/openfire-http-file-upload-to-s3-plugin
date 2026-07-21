/*
 * Licensed under the Apache License, Version 2.0.
 */
package org.igniterealtime.openfire.plugins.s3fileupload;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.xmpp.component.ComponentException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3UploadComponentLifecycleTest {
    @Test
    void replacesRegisteredComponentAndClosesPreviousService() {
        final FakeRegistry registry = new FakeRegistry();
        final List<FakeSlotService> services = new ArrayList<>();
        final S3UploadComponentLifecycle lifecycle = lifecycle(registry, services);

        assertTrue(lifecycle.install(configuration("upload")));
        assertTrue(lifecycle.install(configuration("files")));

        assertEquals("files", lifecycle.registeredSubdomain());
        assertEquals(List.of("upload"), registry.removedSubdomains);
        assertTrue(services.get(0).closed);
        assertFalse(services.get(1).closed);
        assertEquals(0, lifecycle.retiredComponentCount());
    }

    @Test
    void keepsPreviousComponentWhenRegistrationFails() {
        final FakeRegistry registry = new FakeRegistry();
        final List<FakeSlotService> services = new ArrayList<>();
        final S3UploadComponentLifecycle lifecycle = lifecycle(registry, services);
        assertTrue(lifecycle.install(configuration("upload")));
        registry.failAddAt = "files";

        assertFalse(lifecycle.install(configuration("files")));

        assertEquals("upload", lifecycle.registeredSubdomain());
        assertFalse(services.get(0).closed);
        assertTrue(services.get(1).closed);
        assertEquals(0, lifecycle.retiredComponentCount());
    }

    @Test
    void disablesAndRetriesAComponentThatInitiallyFailsRemoval() {
        final FakeRegistry registry = new FakeRegistry();
        final List<FakeSlotService> services = new ArrayList<>();
        final S3UploadComponentLifecycle lifecycle = lifecycle(registry, services);
        assertTrue(lifecycle.install(configuration("upload")));
        registry.removeFailures = 1;

        assertTrue(lifecycle.install(configuration("files")));

        assertEquals("files", lifecycle.registeredSubdomain());
        assertTrue(services.get(0).closed);
        assertFalse(services.get(1).closed);
        assertEquals(1, lifecycle.retiredComponentCount());

        lifecycle.retryRetiredComponents();

        assertEquals(List.of("upload"), registry.removedSubdomains);
        assertEquals(2, registry.removeAttempts);
        assertEquals(0, lifecycle.retiredComponentCount());
    }

    @Test
    void closeTracksAComponentUntilFailedRemovalCanBeRetried() {
        final FakeRegistry registry = new FakeRegistry();
        final List<FakeSlotService> services = new ArrayList<>();
        final S3UploadComponentLifecycle lifecycle = lifecycle(registry, services);
        assertTrue(lifecycle.install(configuration("upload")));
        registry.removeFailures = 2;

        lifecycle.close();

        assertFalse(lifecycle.isInstalled());
        assertTrue(services.get(0).closed);
        assertEquals(1, lifecycle.retiredComponentCount());

        lifecycle.retryRetiredComponents();

        assertEquals(List.of("upload"), registry.removedSubdomains);
        assertEquals(3, registry.removeAttempts);
        assertEquals(0, lifecycle.retiredComponentCount());
    }

    private static S3UploadComponentLifecycle lifecycle(
        FakeRegistry registry,
        List<FakeSlotService> services
    ) {
        return new S3UploadComponentLifecycle(registry, configuration -> {
            final FakeSlotService service = new FakeSlotService();
            services.add(service);
            return new S3UploadComponent(configuration, service);
        });
    }

    private static S3UploadConfiguration configuration(String subdomain) {
        return new S3UploadConfiguration("files", "us-east-1", "", false, true, "", "", "xmpp", subdomain,
            1024, Duration.ofMinutes(5), Duration.ofDays(7));
    }

    private static final class FakeRegistry implements S3UploadComponentLifecycle.Registry {
        private final List<String> removedSubdomains = new ArrayList<>();
        private String failAddAt;
        private int removeFailures;
        private int removeAttempts;

        @Override
        public void add(String subdomain, S3UploadComponent component) throws ComponentException {
            if (subdomain.equals(failAddAt)) {
                throw new ComponentException("registration failed");
            }
        }

        @Override
        public void remove(String subdomain, S3UploadComponent component) {
            removeAttempts++;
            if (removeFailures > 0) {
                removeFailures--;
                throw new IllegalStateException("removal failed");
            }
            removedSubdomains.add(subdomain);
            component.preComponentShutdown();
        }
    }

}
