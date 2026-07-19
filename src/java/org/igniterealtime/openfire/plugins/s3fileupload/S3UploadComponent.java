/*
 * Licensed under the Apache License, Version 2.0.
 */
package org.igniterealtime.openfire.plugins.s3fileupload;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Function;

import org.dom4j.Element;
import org.xmpp.component.AbstractComponent;
import org.xmpp.packet.IQ;
import org.xmpp.packet.PacketError;

final class S3UploadComponent extends AbstractComponent {
    static final String UPLOAD_NAMESPACE = "urn:xmpp:http:upload:0";
    private static final String DATA_FORMS_NAMESPACE = "jabber:x:data";

    private final Object serviceLock = new Object();
    private final Function<S3UploadConfiguration, UploadSlotService> serviceFactory;
    private volatile S3UploadConfiguration configuration;
    private UploadSlotService slotService;

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
        super(1, 4, true);
        this.configuration = Objects.requireNonNull(configuration);
        this.serviceFactory = Objects.requireNonNull(serviceFactory);
        this.slotService = serviceFactory.apply(configuration);
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
        synchronized (serviceLock) {
            return handleSlotRequest(iq, configuration, slotService);
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

        final long size = parsePositiveLong(request.attributeValue("size"));
        if (size < 0) {
            return badRequest(iq, "size must be a positive integer");
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

    void reconfigure(S3UploadConfiguration newConfiguration) {
        Objects.requireNonNull(newConfiguration);
        final UploadSlotService replacement = serviceFactory.apply(newConfiguration);
        synchronized (serviceLock) {
            final UploadSlotService previous = slotService;
            configuration = newConfiguration;
            slotService = replacement;
            if (previous != null) {
                previous.close();
            }
        }
    }

    @Override
    public void preComponentShutdown() {
        synchronized (serviceLock) {
            if (slotService != null) {
                slotService.close();
                slotService = null;
            }
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
        return filename == null || filename.isBlank()
            || filename.getBytes(StandardCharsets.UTF_8).length > S3UploadConfiguration.MAX_FILENAME_BYTES
            || containsControlCharacter(filename);
    }

    private static boolean isInvalidContentType(String contentType) {
        return contentType.length() > 255 || containsControlCharacter(contentType);
    }

    private static long parsePositiveLong(String value) {
        try {
            final long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : -1;
        } catch (NumberFormatException | NullPointerException e) {
            return -1;
        }
    }

    private static boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }
}
