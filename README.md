# Openfire S3 HTTP File Upload Plugin

An Openfire 5.1.1+ plugin that implements [XEP-0363: HTTP File Upload](https://xmpp.org/extensions/xep-0363.html) with direct-to-S3 presigned URLs.

Openfire handles XMPP discovery and slot requests only. File bytes travel directly between the XMPP client and S3; the plugin does not expose an HTTP servlet, buffer uploads, or store files locally.

## Build and install

Requirements: JDK 17 or newer and Maven 3.9 or newer.

```bash
mvn clean package
```

Rename `target/s3fileupload-openfire-plugin-assembly.jar` to `s3fileupload.jar`, copy it to Openfire's `plugins/` directory, and configure it under **Server → Server Settings → S3 File Upload**.

## Credentials

By default, the plugin uses the AWS SDK v2 credential chain, including Java system properties, environment variables, web identity, shared AWS configuration, ECS task credentials, and EC2 instance profile credentials. Prefer a workload identity or instance/task role when using AWS.

For MinIO and other S3-compatible services, disable the default credential chain and configure a static access key and secret key. The secret is stored as an encrypted Openfire property so that every cluster node can sign URLs, and is never rendered back into the administration page. Restrict access to the Openfire database and administration console accordingly.

The identity needs permission to sign requests for these operations:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["s3:PutObject", "s3:GetObject"],
    "Resource": "arn:aws:s3:::YOUR_BUCKET/YOUR_PREFIX/*"
  }]
}
```

## Configuration

All settings are regular Openfire properties and are shared by cluster nodes.

| Property | Default | Purpose |
| --- | --- | --- |
| `plugin.s3fileupload.bucket` | empty | Required S3 bucket |
| `plugin.s3fileupload.region` | `us-east-1` | Signing region |
| `plugin.s3fileupload.endpoint` | empty | Optional S3-compatible endpoint override |
| `plugin.s3fileupload.pathStyleAccess` | `false` | Enable path-style requests for compatible storage |
| `plugin.s3fileupload.useDefaultAwsCredentials` | `true` | Use the AWS SDK default credential chain instead of static credentials |
| `plugin.s3fileupload.accessKey` | empty | Static access key when the default credential chain is disabled |
| `plugin.s3fileupload.secretKey` | empty | Static secret key when the default credential chain is disabled |
| `plugin.s3fileupload.keyPrefix` | `xmpp-uploads` | Prefix for generated randomized object keys |
| `plugin.s3fileupload.serviceSubdomain` | `upload` | Component subdomain (`upload.example.org`) |
| `plugin.s3fileupload.maxFileSize` | `104857600` | Maximum bytes; `-1` disables the limit |
| `plugin.s3fileupload.putExpirationSeconds` | `300` | Presigned PUT lifetime |
| `plugin.s3fileupload.getExpirationSeconds` | `604800` | Presigned GET lifetime (maximum seven days) |

The GET URL is presigned too, so links stop working after its configured lifetime. If messages must retain permanent download links, use a public/private CDN design with an authorization layer instead of this plugin's presigned-GET mode.

For browser clients, configure bucket CORS to allow `PUT`, `GET`, and `HEAD` from the web client's origin and allow at least `Content-Type` and `Content-Length` request headers.

## Security and behavior

- Only local Openfire users can request slots.
- Object keys contain a random UUID and a sanitized copy of the original filename.
- The requested size is bound into the S3 PUT request, and the content type is bound when supplied.
- Presigned URLs can be used by anyone who obtains them until they expire. Use short lifetimes and encrypted XMPP attachments where appropriate.
- S3 lifecycle policies should delete abandoned or expired uploads; this plugin does not delete objects.

## S3-compatible services

Set the endpoint override, choose the credential mode and, when required, enable path-style access. The service must implement AWS Signature Version 4 presigned PUT and GET requests. Provider-specific maximum signature lifetimes can be shorter than seven days.

Example MinIO-style configuration:

```properties
plugin.s3fileupload.endpoint=https://minio.example.org
plugin.s3fileupload.region=us-east-1
plugin.s3fileupload.pathStyleAccess=true
plugin.s3fileupload.useDefaultAwsCredentials=false
plugin.s3fileupload.accessKey=minio-access-key
plugin.s3fileupload.secretKey=minio-secret-key
```
