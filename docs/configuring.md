# Configuring BlueGenes

## Environment variables

| Envvar | Description | Default |
| ------ | ----------- | ------- |
| SERVER_PORT | Port to be used by BlueGenes web server | 5000 |
| LOGGING_LEVEL | Minimum level for logging | info |
| GOOGLE_ANALYTICS | Google Analytics tracking ID | nil |
| BLUEGENES_TOOL_PATH | Directory on server where BlueGenes tools are installed | ./tools |
| BLUEGENES_DEPLOY_PATH | Custom URL path to host BlueGenes. Must start with `/` and **not** end with `/`, e.g. `/bluegenes`. If you wish to host at root, set to `nil`. | nil |
| BLUEGENES_BACKEND_SERVICE_ROOT | Override BLUEGENES_DEFAULT_SERVICE_ROOT for backend API requests (usually an internal address) | nil |
| BLUEGENES_PUBLIC_URL | Absolute public application URL including deployment path; required for OAuth. Use HTTPS except loopback development. | nil |
| BLUEGENES_ALLIANCE_API_BASE | Base URL of the Alliance of Genome Resources API, used by the Alliance summary block on Gene reports. Empty or `nil` disables the block. | https://www.alliancegenome.org/api |
| BLUEGENES_DEFAULT_SERVICE_ROOT | Default InterMine service to run API requests against | https://www.flymine.org/flymine |
| BLUEGENES_DEFAULT_MINE_NAME | Mine name to display for default mine | FlyMine |
| BLUEGENES_DEFAULT_NAMESPACE | Namespace of the default mine | flymine |
| BLUEGENES_ADDITIONAL_MINES | Additional mines managed by this BlueGenes instance | [] |
| HIDE_REGISTRY_MINES | Disable acquiring and displaying mines from the public InterMine registry | false |

## How to configure

BlueGenes supports the many methods of specifying configuration keys provided by [Yogthos' config](https://github.com/yogthos/config#yogthosconfig). The configuration keys listed above can be specified in uppercase or lowercase, and with dashes, underscores or periods as separators.

### Configuration via `config.edn` files

Copy and paste `config/defaults/config.edn` to `config/dev/config.edn` or `config/prod/config.edn` and tweak it to match your preferences. The correct config file will be used depending on if you start BlueGenes intended for development or for production. Note that `config/defaults/config.edn` will be used when you build an uberjar or docker image, unless you use `lein with-profile prod uberjar` to bundle `config/prod/config.edn` instead.

### OAuth and session deployment

Set `BLUEGENES_PUBLIC_URL=https://your-host.example/bluegenes` and register the
exact callback `https://your-host.example/bluegenes/api/auth/oauth2callback?provider=PROVIDER`
with each provider. `PROVIDER` is the configured InterMine provider name, URL-encoded.
The application path must match `BLUEGENES_DEPLOY_PATH`. Password login remains
available when this setting is absent. For local development, set both values
explicitly, for example `SERVER_PORT=5016` and
`BLUEGENES_PUBLIC_URL=http://localhost:5016/bluegenes`.

OAuth requires InterMine service version 36 and its deferred transaction API.
An older server produces an explicit unsupported-version message. Public mine
roots and namespaces must match the configured mines; the default mine may use
`BLUEGENES_BACKEND_SERVICE_ROOT` for server-to-server traffic. The public root
remains the browser's identity for that mine.

Sessions and pending OAuth transactions are process-local. Serve a browser's
requests on the same process, including provider callbacks; restarting it
invalidates pending attempts. Multiple replicas need session affinity or a shared
atomic implementation before OAuth can work reliably. No credentials are stored
in session snapshots. Cookies are HttpOnly and SameSite=Lax, and Secure when the
public URL uses HTTPS. Authentication and init pages use `Cache-Control: no-store`.

OAuth attempts expire after five minutes, with at most 10,000 active attempts per
process. Expired reservations are reclaimed before admitting new attempts. Browser
contexts are bounded separately at 10,000 and expire after 24 hours of inactivity;
ordinary page views do not allocate them. Completed attempts release their active
reservation on delivery. InterMine independently expires pending transactions and
revokes undelivered tokens, including if this process stops.

A new login or logout cancels the prior pending attempt. If remote cancellation
cannot be confirmed, local logout still succeeds and reports a warning. It cannot
promise that a remote transfer was prevented after a network timeout. Superseded
credentials are revoked individually; explicit account logout retains InterMine's
account-wide temporary-token revocation behavior.

### Analytics (optional)

For now, the only supported statistics service is [Google analytics](https://analytics.google.com/). If you wish to use this service, configure your domain and then add your Google Analytics ID using the `GOOGLE_ANALYTICS` key listed above.

### Alliance tools

`alliance-tools/` holds tools written for this fork. Run `scripts/install-alliance-tools.sh` to copy each package into `$BLUEGENES_TOOL_PATH/@alliance/<name>` (default `./tools`) and add it to `tools.edn`. Start BlueGenes once first: the tool store creates `tools.edn` and installs the official tools only when that file is missing, and the script exits with an error if it is absent. Existing `tools.edn` entries are kept, and rerunning updates the version in place. Restart BlueGenes afterwards. `alliance-tools/bluegenes-alliance-gene-link` shows a link to the gene's alliancegenome.org page on Gene report pages; add it to the report layout through the tool's `bluegenesAllianceGeneLink` name or leave it to the autogenerated layout.
