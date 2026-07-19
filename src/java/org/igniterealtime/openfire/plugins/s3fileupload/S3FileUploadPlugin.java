/*
 * Licensed under the Apache License, Version 2.0.
 */
package org.igniterealtime.openfire.plugins.s3fileupload;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.util.PropertyEventDispatcher;
import org.jivesoftware.util.PropertyEventListener;
import org.jivesoftware.util.SystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class S3FileUploadPlugin implements Plugin, PropertyEventListener {
    private static final Logger Log = LoggerFactory.getLogger(S3FileUploadPlugin.class);
    private static final String PROPERTY_PREFIX = "plugin.s3fileupload.";
    private static final String PLUGIN_NAME = "S3 HTTP File Upload";

    public static final SystemProperty<String> BUCKET = stringProperty("bucket", "");
    public static final SystemProperty<String> REGION = stringProperty("region", "us-east-1");
    public static final SystemProperty<String> ENDPOINT = stringProperty("endpoint", "");
    public static final SystemProperty<Boolean> PATH_STYLE_ACCESS = booleanProperty("pathStyleAccess", false);
    public static final SystemProperty<Boolean> USE_DEFAULT_AWS_CREDENTIALS =
        booleanProperty("useDefaultAwsCredentials", true);
    public static final SystemProperty<String> ACCESS_KEY = stringProperty("accessKey", "");
    public static final SystemProperty<String> SECRET_KEY = stringProperty("secretKey", "", true);
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

    private final S3UploadComponentLifecycle componentLifecycle = new S3UploadComponentLifecycle();
    private S3UploadConfiguration appliedConfiguration;
    private boolean initialized;
    private boolean reloadSuspended;

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
        componentLifecycle.close();
        appliedConfiguration = null;
        Log.info("S3 HTTP File Upload plugin destroyed");
    }

    public synchronized void reloadConfiguration() {
        if (!initialized || reloadSuspended) {
            return;
        }
        try {
            componentLifecycle.retryRetiredComponents();
            final S3UploadConfiguration configuration = configuration();
            if (configuration.equals(appliedConfiguration)) {
                return;
            }

            if (!configuration.hasValidServiceSubdomain()) {
                Log.error("Cannot register S3 upload component: invalid service subdomain '{}'.",
                    configuration.serviceSubdomain());
                if (componentLifecycle.isInstalled()) {
                    componentLifecycle.reconfigure(configuration);
                }
            } else if (!componentLifecycle.isInstalledAt(configuration.serviceSubdomain())) {
                if (!componentLifecycle.install(configuration)) {
                    return; // leave appliedConfiguration stale so the next property event retries
                }
            } else {
                componentLifecycle.reconfigure(configuration);
            }
            appliedConfiguration = configuration;
            if (configuration.isReady()) {
                Log.info("S3 upload service configured for bucket '{}' in region '{}' at component subdomain '{}'.",
                    configuration.bucket(), configuration.region(), componentLifecycle.registeredSubdomain());
            } else {
                Log.warn("S3 upload service is not ready: {}", String.join("; ", configuration.validationErrors()));
            }
        } catch (RuntimeException e) {
            Log.error("Unable to apply S3 upload configuration.", e);
        }
    }

    /**
     * Applies all settings in one step, reloading the service once instead of per property.
     * The configuration must be valid. Cluster note: remote nodes still observe the underlying
     * property changes one at a time and may briefly reload against mixed old/new values.
     */
    public synchronized boolean applyConfiguration(S3UploadConfiguration configuration) {
        final List<String> errors = configuration.validationErrors();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        reloadSuspended = true;
        try {
            BUCKET.setValue(configuration.bucket());
            REGION.setValue(configuration.region());
            ENDPOINT.setValue(configuration.endpoint());
            PATH_STYLE_ACCESS.setValue(configuration.pathStyleAccess());
            USE_DEFAULT_AWS_CREDENTIALS.setValue(configuration.useDefaultAwsCredentials());
            ACCESS_KEY.setValue(configuration.accessKey());
            SECRET_KEY.setValue(configuration.secretKey());
            KEY_PREFIX.setValue(configuration.keyPrefix());
            SERVICE_SUBDOMAIN.setValue(configuration.serviceSubdomain());
            MAX_FILE_SIZE.setValue(configuration.maxFileSize());
            PUT_EXPIRATION_SECONDS.setValue(Math.toIntExact(configuration.putExpiration().toSeconds()));
            GET_EXPIRATION_SECONDS.setValue(Math.toIntExact(configuration.getExpiration().toSeconds()));
            return true;
        } catch (RuntimeException e) {
            Log.error("Unable to persist S3 upload configuration.", e);
            return false;
        } finally {
            // Reload even when a setValue fails part-way: whatever was persisted must be applied.
            reloadSuspended = false;
            reloadConfiguration();
        }
    }

    /** The subdomain the component is actually registered at, or null when registration failed. */
    public synchronized String registeredSubdomain() {
        return componentLifecycle.registeredSubdomain();
    }

    public S3UploadConfiguration configuration() {
        return new S3UploadConfiguration(
            BUCKET.getValue(),
            REGION.getValue(),
            ENDPOINT.getValue(),
            PATH_STYLE_ACCESS.getValue(),
            USE_DEFAULT_AWS_CREDENTIALS.getValue(),
            ACCESS_KEY.getValue(),
            SECRET_KEY.getValue(),
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

    private static SystemProperty<String> stringProperty(String suffix, String defaultValue) {
        return stringProperty(suffix, defaultValue, false);
    }

    private static SystemProperty<String> stringProperty(
        String suffix,
        String defaultValue,
        boolean encrypted
    ) {
        return SystemProperty.Builder.ofType(String.class)
            .setKey(PROPERTY_PREFIX + suffix)
            .setDefaultValue(defaultValue)
            .setDynamic(true)
            .setEncrypted(encrypted)
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

    private static SystemProperty<Boolean> booleanProperty(String suffix, boolean defaultValue) {
        return SystemProperty.Builder.ofType(Boolean.class)
            .setKey(PROPERTY_PREFIX + suffix)
            .setDefaultValue(defaultValue)
            .setDynamic(true)
            .setPlugin(PLUGIN_NAME)
            .build();
    }
}
