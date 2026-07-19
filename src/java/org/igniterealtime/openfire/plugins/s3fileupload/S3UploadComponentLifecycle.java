/*
 * Licensed under the Apache License, Version 2.0.
 */
package org.igniterealtime.openfire.plugins.s3fileupload;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.jivesoftware.openfire.component.InternalComponentManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.component.ComponentException;

final class S3UploadComponentLifecycle implements AutoCloseable {
    private static final Logger Log = LoggerFactory.getLogger(S3UploadComponentLifecycle.class);

    interface Registry {
        void add(String subdomain, S3UploadComponent component) throws ComponentException;

        void remove(String subdomain, S3UploadComponent component);
    }

    private final Registry registry;
    private final Function<S3UploadConfiguration, S3UploadComponent> componentFactory;
    private final List<Registration> retiredComponents = new ArrayList<>();
    private S3UploadComponent component;
    private String registeredSubdomain;

    S3UploadComponentLifecycle() {
        this(new Registry() {
            @Override
            public void add(String subdomain, S3UploadComponent component) throws ComponentException {
                InternalComponentManager.getInstance().addComponent(subdomain, component);
            }

            @Override
            public void remove(String subdomain, S3UploadComponent component) {
                InternalComponentManager.getInstance().removeComponent(subdomain, component);
            }
        }, S3UploadComponent::new);
    }

    S3UploadComponentLifecycle(
        Registry registry,
        Function<S3UploadConfiguration, S3UploadComponent> componentFactory
    ) {
        this.registry = Objects.requireNonNull(registry);
        this.componentFactory = Objects.requireNonNull(componentFactory);
    }

    boolean isInstalled() {
        return component != null;
    }

    boolean isInstalledAt(String subdomain) {
        return component != null && Objects.equals(subdomain, registeredSubdomain);
    }

    String registeredSubdomain() {
        return registeredSubdomain;
    }

    void reconfigure(S3UploadConfiguration configuration) {
        if (component != null) {
            component.reconfigure(configuration);
        }
    }

    boolean install(S3UploadConfiguration configuration) {
        final S3UploadComponent candidate = componentFactory.apply(configuration);
        try {
            registry.add(configuration.serviceSubdomain(), candidate);
        } catch (ComponentException | RuntimeException e) {
            candidate.preComponentShutdown();
            Log.error("Unable to register S3 upload component at subdomain '{}'.",
                configuration.serviceSubdomain(), e);
            return false;
        }

        final S3UploadComponent previous = component;
        final String previousSubdomain = registeredSubdomain;
        component = candidate;
        registeredSubdomain = configuration.serviceSubdomain();
        if (previous != null) {
            retiredComponents.add(new Registration(previousSubdomain, previous));
            retryRetiredComponents();
        }
        return true;
    }

    void retryRetiredComponents() {
        final Iterator<Registration> iterator = retiredComponents.iterator();
        while (iterator.hasNext()) {
            final Registration retired = iterator.next();
            try {
                registry.remove(retired.subdomain(), retired.component());
                iterator.remove();
            } catch (RuntimeException e) {
                // Stop a stale registration from issuing URLs while its removal is retried.
                retired.component().preComponentShutdown();
                Log.warn("Unable to remove retired S3 upload component at subdomain '{}'; will retry.",
                    retired.subdomain(), e);
            }
        }
    }

    int retiredComponentCount() {
        return retiredComponents.size();
    }

    @Override
    public void close() {
        if (component != null) {
            try {
                registry.remove(registeredSubdomain, component);
            } catch (RuntimeException e) {
                component.preComponentShutdown();
                Log.warn("Unable to remove S3 upload component at subdomain '{}'.", registeredSubdomain, e);
            }
            component = null;
            registeredSubdomain = null;
        }
        retryRetiredComponents();
    }

    private record Registration(String subdomain, S3UploadComponent component) {
    }
}
