/*
 * Licensed under the Apache License, Version 2.0.
 */
package org.igniterealtime.openfire.plugins.s3fileupload;

import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Function;

import org.dom4j.Element;
import org.xmpp.component.AbstractComponent;
import org.xmpp.packet.IQ;
import org.xmpp.packet.PacketError;

final class S3UploadComponent extends AbstractComponent {
    static final String UPLOAD_NAMESPACE = "urn:xmpp:http:upload:0";
    private static final String DATA_FORMS_NAMESPACE = "jabber:x:data";

    private final Object serviceLifecycleMonitor = new Object();
    private final IdentityHashMap<UploadSlotService, ServiceState> serviceStates = new IdentityHashMap<>();
    private final Function<S3UploadConfiguration, UploadSlotService> serviceFactory;
    private volatile S3UploadConfiguration configuration;
    private UploadSlotService slotService;
    private boolean shutdown; // guarded by serviceLifecycleMonitor

    S3UploadComponent(S3UploadConfiguration configuration) {
        this(configuration, S3UploadComponent::createService);
    }

    S3UploadComponent(S3UploadConfiguration configuration, UploadSlotService slotService) {
        this(configuration, ignored -> slotService);
    }

    S3UploadComponent(
        S3UploadConfiguration configuration,
        Function<S3UploadConfiguration, UploadSlotService> serviceFactory
    ) {
        super(4, 1000, true); // presigning is cheap local work; a modest pool absorbs bursts
        this.configuration = Objects.requireNonNull(configuration);
        this.serviceFactory = Objects.requireNonNull(serviceFactory);
        this.slotService = serviceFactory.apply(configuration);
        if (slotService != null) {
            serviceStates.put(slotService, new ServiceState());
        }
    }

    @Override
    public String getName() {
        return "S3 HTTP File Upload";
    }

    @Override
    public String getDescription() {
        return "XEP-0363 HTTP File Upload using S3 presigned URLs";
    }

    @Override
    protected String discoInfoIdentityCategory() {
        return "store";
    }

    @Override
    protected String discoInfoIdentityCategoryType() {
        return "file";
    }

    @Override
    protected String[] discoInfoFeatureNamespaces() {
        return new String[] {UPLOAD_NAMESPACE};
    }

    @Override
    public boolean servesLocalUsersOnly() {
        return true;
    }

    @Override
    protected IQ handleDiscoInfo(IQ iq) {
        final IQ response = super.handleDiscoInfo(iq);
        final Element query = response.getChildElement();
        final Element form = query.addElement("x", DATA_FORMS_NAMESPACE);
        form.addAttribute("type", "result");

        final Element formType = form.addElement("field");
        formType.addAttribute("var", "FORM_TYPE");
        formType.addAttribute("type", "hidden");
        formType.addElement("value").setText(UPLOAD_NAMESPACE);

        if (configuration.maxFileSize() > -1) {
            final Element maxFileSize = form.addElement("field");
            maxFileSize.addAttribute("var", "max-file-size");
            maxFileSize.addElement("value").setText(Long.toString(configuration.maxFileSize()));
        }
        return response;
    }

    @Override
    protected IQ handleIQGet(IQ iq) throws Exception {
        final Element request = iq.getChildElement();
        if (request == null || !"request".equals(request.getName())
            || !UPLOAD_NAMESPACE.equals(request.getNamespaceURI())) {
            return super.handleIQGet(iq);
        }
        return handleSlotRequest(iq);
    }

    IQ handleSlotRequest(IQ iq) {
        final ServiceLease lease = acquireService();
        try {
            return handleSlotRequest(iq, lease.configuration(), lease.service());
        } finally {
            releaseService(lease.service());
        }
    }

    private IQ handleSlotRequest(IQ iq, S3UploadConfiguration current, UploadSlotService service) {
        if (!current.isReady()) {
            return error(iq, PacketError.Condition.service_unavailable, PacketError.Type.cancel,
                "S3 upload is not configured");
        }
        if (iq.getFrom() == null) {
            return error(iq, PacketError.Condition.not_authorized, PacketError.Type.auth,
                "An authenticated requester is required");
        }

        final Element request = iq.getChildElement();
        final String filename = request.attributeValue("filename");
        if (isInvalidFilename(filename)) {
            return badRequest(iq,
                "filename must contain 1 to 255 UTF-8 bytes and no control characters");
        }

        final long size = parseSize(request.attributeValue("size"));
        if (size < 0) {
            return badRequest(iq, "size must be a non-negative integer");
        }

        if (current.maxFileSize() > -1 && size > current.maxFileSize()) {
            final IQ response = error(iq, PacketError.Condition.not_acceptable, PacketError.Type.modify,
                "The requested file is too large");
            final Element applicationError = response.getError().getElement()
                .addElement("file-too-large", UPLOAD_NAMESPACE);
            applicationError.addElement("max-file-size").setText(Long.toString(current.maxFileSize()));
            return response;
        }

        final String contentType = request.attributeValue("content-type");
        if (contentType != null && isInvalidContentType(contentType)) {
            return badRequest(iq, "content-type is invalid");
        }

        try {
            if (service == null) {
                return error(iq, PacketError.Condition.service_unavailable, PacketError.Type.cancel,
                    "S3 upload is not available");
            }
            final UploadSlot slot = service.createSlot(new UploadRequest(filename, size, contentType));
            final IQ response = IQ.createResultIQ(iq);
            final Element slotElement = response.setChildElement("slot", UPLOAD_NAMESPACE);
            slotElement.addElement("put").addAttribute("url", slot.putUrl());
            slotElement.addElement("get").addAttribute("url", slot.getUrl());
            return response;
        } catch (RuntimeException e) {
            log.warn("Unable to create an S3 upload slot for {}", iq.getFrom(), e);
            return error(iq, PacketError.Condition.internal_server_error, PacketError.Type.wait,
                "Unable to create an upload slot");
        }
    }

    @SuppressWarnings("ReferenceEquality") // guards against closing the service we just installed
    void reconfigure(S3UploadConfiguration newConfiguration) {
        Objects.requireNonNull(newConfiguration);
        final UploadSlotService replacement = serviceFactory.apply(newConfiguration);
        final UploadSlotService serviceToClose;
        synchronized (serviceLifecycleMonitor) {
            if (shutdown) {
                // A racing shutdown won. Retire the replacement without waiting for any
                // request that might already be using the same service instance.
                serviceToClose = retireService(replacement);
            } else {
                final UploadSlotService previous = slotService;
                configuration = newConfiguration;
                slotService = replacement;
                if (replacement != null) {
                    serviceStates.computeIfAbsent(replacement, ignored -> new ServiceState()).retired = false;
                }
                serviceToClose = previous != replacement ? retireService(previous) : null;
            }
        }
        closeQuietly(serviceToClose);
    }

    @Override
    public void preComponentShutdown() {
        final UploadSlotService serviceToClose;
        synchronized (serviceLifecycleMonitor) {
            shutdown = true;
            serviceToClose = retireService(slotService);
            slotService = null;
        }
        closeQuietly(serviceToClose);
    }

    private ServiceLease acquireService() {
        synchronized (serviceLifecycleMonitor) {
            final UploadSlotService service = slotService;
            if (service != null) {
                serviceStates.computeIfAbsent(service, ignored -> new ServiceState()).activeRequests++;
            }
            return new ServiceLease(configuration, service);
        }
    }

    private void releaseService(UploadSlotService service) {
        if (service == null) {
            return;
        }
        final UploadSlotService serviceToClose;
        synchronized (serviceLifecycleMonitor) {
            final ServiceState state = serviceStates.get(service);
            state.activeRequests--;
            serviceToClose = state.retired && state.activeRequests == 0 ? markClosed(service, state) : null;
        }
        closeQuietly(serviceToClose);
    }

    private UploadSlotService retireService(UploadSlotService service) {
        if (service == null) {
            return null;
        }
        final ServiceState state = serviceStates.computeIfAbsent(service, ignored -> new ServiceState());
        state.retired = true;
        return state.activeRequests == 0 ? markClosed(service, state) : null;
    }

    private static UploadSlotService markClosed(UploadSlotService service, ServiceState state) {
        if (state.closed) {
            return null;
        }
        state.closed = true;
        return service;
    }

    private void closeQuietly(UploadSlotService service) {
        if (service == null) {
            return;
        }
        try {
            service.close();
        } catch (RuntimeException e) {
            log.warn("Failed to close an S3 upload slot service.", e);
        }
    }

    private static UploadSlotService createService(S3UploadConfiguration configuration) {
        return configuration.isReady() ? new S3PresignedUrlService(configuration) : null;
    }

    private static IQ error(IQ request, PacketError.Condition condition, PacketError.Type type, String text) {
        final IQ response = IQ.createResultIQ(request);
        response.setType(IQ.Type.error);
        if (request.getChildElement() != null) {
            response.setChildElement(request.getChildElement().createCopy());
        }
        response.setError(new PacketError(condition, type, text));
        return response;
    }

    private static IQ badRequest(IQ request, String text) {
        return error(request, PacketError.Condition.bad_request, PacketError.Type.modify, text);
    }

    private static boolean isInvalidFilename(String filename) {
        // "." and ".." survive into the object key as a URL path segment, which HTTP clients
        // normalize away before sending - breaking the SigV4 signature.
        return filename == null || filename.isBlank()
            || ".".equals(filename) || "..".equals(filename)
            || filename.getBytes(StandardCharsets.UTF_8).length > S3UploadConfiguration.MAX_FILENAME_BYTES
            || containsControlCharacter(filename);
    }

    private static boolean isInvalidContentType(String contentType) {
        return contentType.length() > 255 || containsControlCharacter(contentType);
    }

    private static long parseSize(String value) {
        if (value == null) {
            return -1;
        }
        try {
            final long parsed = Long.parseLong(value);
            return parsed >= 0 ? parsed : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private record ServiceLease(S3UploadConfiguration configuration, UploadSlotService service) {
    }

    private static final class ServiceState {
        private int activeRequests;
        private boolean retired;
        private boolean closed;
    }
}
