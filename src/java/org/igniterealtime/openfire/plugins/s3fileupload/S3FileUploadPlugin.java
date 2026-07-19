/*
 * Licensed under the Apache License, Version 2.0.
 */
package org.igniterealtime.openfire.plugins.s3fileupload;

import java.io.File;
import java.time.Duration;
import java.util.Map;

import org.jivesoftware.openfire.component.InternalComponentManager;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.util.PropertyEventDispatcher;
import org.jivesoftware.util.PropertyEventListener;
import org.jivesoftware.util.SystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.component.ComponentException;

public final class S3FileUploadPlugin implements Plugin, PropertyEventListener {
    private static final Logger Log = LoggerFactory.getLogger(S3FileUploadPlugin.class);
    private static final String PROPERTY_PREFIX = "plugin.s3fileupload.";
    private static final String PLUGIN_NAME = "S3 HTTP File Upload";

    public static final SystemProperty<String> BUCKET = stringProperty("bucket", "");
    public static final SystemProperty<String> REGION = stringProperty("region", "us-east-1");
    public static final SystemProperty<String> ENDPOINT = stringProperty("endpoint", "");
    public static final SystemProperty<Boolean> PATH_STYLE_ACCESS = SystemProperty.Builder.ofType(Boolean.class)
        .setKey(PROPERTY_PREFIX + "pathStyleAccess")
        .setDefaultValue(false)
        .setDynamic(true)
        .setPlugin(PLUGIN_NAME)
        .build();
    public static final SystemProperty<String> KEY_PREFIX = stringProperty("keyPrefix", "xmpp-uploads");
    public static final SystemProperty<String> SERVICE_SUBDOMAIN = stringProperty("serviceSubdomain", "upload");
    public static final SystemProperty<Long> MAX_FILE_SIZE = SystemProperty.Builder.ofType(Long.class)
        .setKey(PROPERTY_PREFIX + "maxFileSize")
        .setDefaultValue(104_857_600L)
        .setDynamic(true)
        .setPlugin(PLUGIN_NAME)
        .build();
    public static final SystemProperty<Integer> PUT_EXPIRATION_SECONDS = integerProperty("putExpirationSeconds", 300);
    public static final SystemProperty<Integer> GET_EXPIRATION_SECONDS = integerProperty("getExpirationSeconds", 604_800);

    private S3UploadComponent component;
    private String registeredSubdomain;
    private boolean initialized;

    @Override
    public synchronized void initializePlugin(PluginManager manager, File pluginDirectory) {
        PropertyEventDispatcher.addListener(this);
        initialized = true;
        reloadConfiguration();
        Log.info("S3 HTTP File Upload plugin initialized");
    }

    @Override
    public synchronized void destroyPlugin() {
        initialized = false;
        PropertyEventDispatcher.removeListener(this);
        if (component != null && registeredSubdomain != null) {
            InternalComponentManager.getInstance().removeComponent(registeredSubdomain, component);
        }
        component = null;
        registeredSubdomain = null;
        Log.info("S3 HTTP File Upload plugin destroyed");
    }

    public synchronized void reloadConfiguration() {
        if (!initialized) {
            return;
        }
        final S3UploadConfiguration configuration = configuration();
        final String requestedSubdomain = configuration.serviceSubdomain();

        if ((component == null || !requestedSubdomain.equals(registeredSubdomain))
            && configuration.hasValidServiceSubdomain()) {
            installComponent(configuration);
        } else if (component != null) {
            component.reconfigure(configuration);
        } else {
            Log.error("Cannot register S3 upload component: invalid service subdomain '{}'.", requestedSubdomain);
        }

        if (configuration.isReady()) {
            Log.info("S3 upload service configured for bucket '{}' in region '{}' at component subdomain '{}'.",
                configuration.bucket(), configuration.region(), registeredSubdomain);
        } else {
            Log.warn("S3 upload service is not ready: {}", String.join("; ", configuration.validationErrors()));
        }
    }

    public S3UploadConfiguration configuration() {
        return new S3UploadConfiguration(
            BUCKET.getValue(),
            REGION.getValue(),
            ENDPOINT.getValue(),
            PATH_STYLE_ACCESS.getValue(),
            KEY_PREFIX.getValue(),
            SERVICE_SUBDOMAIN.getValue(),
            MAX_FILE_SIZE.getValue(),
            Duration.ofSeconds(PUT_EXPIRATION_SECONDS.getValue()),
            Duration.ofSeconds(GET_EXPIRATION_SECONDS.getValue())
        );
    }

    @Override
    public void propertySet(String property, Map params) {
        if (property.startsWith(PROPERTY_PREFIX)) {
            reloadConfiguration();
        }
    }

    @Override
    public void propertyDeleted(String property, Map params) {
        propertySet(property, params);
    }

    @Override
    public void xmlPropertySet(String property, Map params) {
        // This plugin intentionally uses database-backed Openfire properties for cluster-wide consistency.
    }

    @Override
    public void xmlPropertyDeleted(String property, Map params) {
        // This plugin intentionally uses database-backed Openfire properties for cluster-wide consistency.
    }

    private void installComponent(S3UploadConfiguration configuration) {
        final S3UploadComponent previous = component;
        final String previousSubdomain = registeredSubdomain;
        final S3UploadComponent candidate = new S3UploadComponent(configuration);
        try {
            InternalComponentManager.getInstance().addComponent(configuration.serviceSubdomain(), candidate);
            component = candidate;
            registeredSubdomain = configuration.serviceSubdomain();
            if (previous != null) {
                InternalComponentManager.getInstance().removeComponent(previousSubdomain, previous);
            }
        } catch (ComponentException e) {
            candidate.preComponentShutdown();
            if (previous != null) {
                previous.reconfigure(configuration);
            }
            Log.error("Unable to register S3 upload component at subdomain '{}'.",
                configuration.serviceSubdomain(), e);
        }
    }

    private static SystemProperty<String> stringProperty(String suffix, String defaultValue) {
        return SystemProperty.Builder.ofType(String.class)
            .setKey(PROPERTY_PREFIX + suffix)
            .setDefaultValue(defaultValue)
            .setDynamic(true)
            .setPlugin(PLUGIN_NAME)
            .build();
    }

    private static SystemProperty<Integer> integerProperty(String suffix, int defaultValue) {
        return SystemProperty.Builder.ofType(Integer.class)
            .setKey(PROPERTY_PREFIX + suffix)
            .setDefaultValue(defaultValue)
            .setDynamic(true)
            .setPlugin(PLUGIN_NAME)
            .build();
    }
}
