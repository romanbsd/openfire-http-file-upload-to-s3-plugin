<%@ page contentType="text/html; charset=UTF-8" %>
<%@ page errorPage="/error.jsp" %>
<%@ page import="java.time.Duration" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.List" %>
<%@ page import="org.igniterealtime.openfire.plugins.s3fileupload.S3FileUploadPlugin" %>
<%@ page import="org.igniterealtime.openfire.plugins.s3fileupload.S3UploadConfiguration" %>
<%@ page import="org.jivesoftware.openfire.XMPPServer" %>
<%@ page import="org.jivesoftware.util.ParamUtils" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>
<%@ taglib uri="admin" prefix="admin" %>
<jsp:useBean id="webManager" class="org.jivesoftware.util.WebManager" />
<% webManager.init(request, response, session, application, out); %>
<%
    final S3FileUploadPlugin plugin = (S3FileUploadPlugin) XMPPServer.getInstance()
        .getPluginManager().getPluginByName("S3 HTTP File Upload").orElseThrow();
    final S3UploadConfiguration existingConfiguration = plugin.configuration();
    final List<String> errors = new ArrayList<>();
    boolean saved = false;
    boolean saveFailed = false;

    if ("POST".equals(request.getMethod()) && request.getParameter("update") != null) {
        final String bucket = request.getParameter("bucket");
        final String region = request.getParameter("region");
        final String endpoint = request.getParameter("endpoint");
        final boolean pathStyleAccess = ParamUtils.getBooleanParameter(request, "pathStyleAccess");
        final boolean useDefaultAwsCredentials =
            ParamUtils.getBooleanParameter(request, "useDefaultAwsCredentials");
        final String submittedAccessKey = request.getParameter("accessKey");
        final String submittedSecretKey = request.getParameter("secretKey");
        final String accessKey = useDefaultAwsCredentials ? "" : submittedAccessKey;
        final String secretKey = useDefaultAwsCredentials ? ""
            : submittedSecretKey == null || submittedSecretKey.isEmpty()
                ? existingConfiguration.secretKey() : submittedSecretKey;
        final String keyPrefix = request.getParameter("keyPrefix");
        final String serviceSubdomain = request.getParameter("serviceSubdomain");
        final long maxFileSize = ParamUtils.getLongParameter(request, "maxFileSize",
            S3FileUploadPlugin.MAX_FILE_SIZE.getDefaultValue());
        final int putExpirationSeconds = ParamUtils.getIntParameter(request, "putExpirationSeconds",
            S3FileUploadPlugin.PUT_EXPIRATION_SECONDS.getDefaultValue());
        final int getExpirationSeconds = ParamUtils.getIntParameter(request, "getExpirationSeconds",
            S3FileUploadPlugin.GET_EXPIRATION_SECONDS.getDefaultValue());

        final S3UploadConfiguration candidate = new S3UploadConfiguration(
            bucket, region, endpoint, pathStyleAccess, useDefaultAwsCredentials, accessKey, secretKey,
            keyPrefix, serviceSubdomain, maxFileSize,
            Duration.ofSeconds(putExpirationSeconds), Duration.ofSeconds(getExpirationSeconds));
        errors.addAll(candidate.validationErrors());

        if (errors.isEmpty()) {
            if (plugin.applyConfiguration(candidate)) {
                webManager.logEvent("Changed S3 HTTP File Upload settings",
                    "bucket=" + candidate.bucket() + ", region=" + candidate.region()
                        + ", endpoint=" + candidate.endpoint()
                        + ", pathStyleAccess=" + candidate.pathStyleAccess()
                        + ", keyPrefix=" + candidate.keyPrefix()
                        + ", useDefaultAwsCredentials=" + candidate.useDefaultAwsCredentials()
                        + ", serviceSubdomain=" + candidate.serviceSubdomain()
                        + ", maxFileSize=" + candidate.maxFileSize()
                        + ", putExpirationSeconds=" + putExpirationSeconds
                        + ", getExpirationSeconds=" + getExpirationSeconds);
                saved = true;
            } else {
                saveFailed = true;
            }
        }
    }

    final S3UploadConfiguration configuration = plugin.configuration();
    request.setAttribute("errors", errors);
    request.setAttribute("saved", saved);
    request.setAttribute("saveFailed", saveFailed);
    request.setAttribute("ready", configuration.isReady());
    request.setAttribute("bucket", configuration.bucket());
    request.setAttribute("region", configuration.region());
    request.setAttribute("endpoint", configuration.endpoint());
    request.setAttribute("pathStyleAccess", configuration.pathStyleAccess());
    request.setAttribute("useDefaultAwsCredentials", configuration.useDefaultAwsCredentials());
    request.setAttribute("accessKey", configuration.accessKey());
    request.setAttribute("secretKeyConfigured", !configuration.secretKey().isEmpty());
    request.setAttribute("keyPrefix", configuration.keyPrefix());
    request.setAttribute("serviceSubdomain", configuration.serviceSubdomain());
    request.setAttribute("maxFileSize", configuration.maxFileSize());
    request.setAttribute("putExpirationSeconds", configuration.putExpiration().getSeconds());
    request.setAttribute("getExpirationSeconds", configuration.getExpiration().getSeconds());
    request.setAttribute("serviceDomain", configuration.serviceSubdomain() + "."
        + XMPPServer.getInstance().getServerInfo().getXMPPDomain());
    request.setAttribute("componentRegistered",
        configuration.serviceSubdomain().equals(plugin.registeredSubdomain()));
%>
<html>
<head>
    <title><fmt:message key="s3fileupload.settings.title" /></title>
    <meta name="pageID" content="s3fileupload-settings" />
</head>
<body>
<admin:FlashMessage />

<c:if test="${saved}">
    <admin:infobox type="success"><fmt:message key="s3fileupload.settings.saved" /></admin:infobox>
</c:if>
<c:if test="${saveFailed}">
    <admin:infobox type="error"><fmt:message key="s3fileupload.settings.saveFailed" /></admin:infobox>
</c:if>
<c:if test="${not empty errors}">
    <admin:infobox type="error">
        <ul>
            <c:forEach var="error" items="${errors}"><li><c:out value="${error}" /></li></c:forEach>
        </ul>
    </admin:infobox>
</c:if>
<c:if test="${not ready}">
    <admin:infobox type="warning"><fmt:message key="s3fileupload.settings.notReady" /></admin:infobox>
</c:if>
<c:if test="${ready and not componentRegistered}">
    <admin:infobox type="error"><fmt:message key="s3fileupload.settings.notRegistered" /></admin:infobox>
</c:if>

<p><fmt:message key="s3fileupload.settings.description" /></p>
<p><fmt:message key="s3fileupload.settings.credentials" /></p>
<p><fmt:message key="s3fileupload.settings.serviceDomain"><fmt:param value="${serviceDomain}" /></fmt:message></p>

<form action="s3fileupload-settings.jsp" method="post">
    <input type="hidden" name="csrf" value="<c:out value='${csrf}'/>" />

    <admin:contentBox title="S3">
        <table>
            <tr><td><label for="bucket">Bucket</label></td><td><input id="bucket" name="bucket" size="50" maxlength="255" value="<c:out value='${bucket}'/>" required /></td></tr>
            <tr><td><label for="region">Region</label></td><td><input id="region" name="region" size="30" maxlength="100" value="<c:out value='${region}'/>" required /></td></tr>
            <tr><td><label for="endpoint">Endpoint override</label></td><td><input id="endpoint" name="endpoint" size="70" maxlength="500" value="<c:out value='${endpoint}'/>" placeholder="https://s3.example.com" /></td></tr>
            <tr><td><label for="pathStyleAccess">Path-style access</label></td><td><input id="pathStyleAccess" name="pathStyleAccess" type="checkbox" <c:if test="${pathStyleAccess}">checked</c:if> /></td></tr>
            <tr><td><label for="useDefaultAwsCredentials">Default AWS credential chain</label></td><td><input id="useDefaultAwsCredentials" name="useDefaultAwsCredentials" type="checkbox" <c:if test="${useDefaultAwsCredentials}">checked</c:if> /> Use environment, system properties, profiles, web identity, ECS or EC2 credentials</td></tr>
            <tr><td><label for="accessKey">Access key</label></td><td><input id="accessKey" name="accessKey" size="50" maxlength="255" autocomplete="off" value="<c:out value='${accessKey}'/>" /> Required when the default credential chain is disabled</td></tr>
            <tr><td><label for="secretKey">Secret key</label></td><td><input id="secretKey" name="secretKey" type="password" size="50" maxlength="1024" autocomplete="new-password" value="" placeholder="Leave blank to keep the configured secret" /> <c:if test="${secretKeyConfigured}">A secret key is configured.</c:if></td></tr>
            <tr><td><label for="keyPrefix">Object key prefix</label></td><td><input id="keyPrefix" name="keyPrefix" size="50" maxlength="500" value="<c:out value='${keyPrefix}'/>" /></td></tr>
        </table>
    </admin:contentBox>

    <admin:contentBox title="XEP-0363 service">
        <table>
            <tr><td><label for="serviceSubdomain">Service subdomain</label></td><td><input id="serviceSubdomain" name="serviceSubdomain" size="30" maxlength="253" value="<c:out value='${serviceSubdomain}'/>" required /></td></tr>
            <tr><td><label for="maxFileSize">Maximum file size (bytes)</label></td><td><input id="maxFileSize" name="maxFileSize" type="number" min="-1" value="${maxFileSize}" required /></td></tr>
            <tr><td><label for="putExpirationSeconds">PUT URL lifetime (seconds)</label></td><td><input id="putExpirationSeconds" name="putExpirationSeconds" type="number" min="1" max="604800" value="${putExpirationSeconds}" required /></td></tr>
            <tr><td><label for="getExpirationSeconds">GET URL lifetime (seconds)</label></td><td><input id="getExpirationSeconds" name="getExpirationSeconds" type="number" min="1" max="604800" value="${getExpirationSeconds}" required /></td></tr>
        </table>
    </admin:contentBox>

    <input type="submit" name="update" value="Save settings" />
</form>
</body>
</html>
