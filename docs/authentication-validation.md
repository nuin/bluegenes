# Java 21 authentication validation

The paired CI workflow uses InterMine commit
`5d246cd54d4b6ab2810dcac8fc91ad9325c7ba94`, including the final authentication review fixes and
checksum-pinned Solr downloads from Apache's primary CDN. The
[backend commit](https://github.com/nuin/intermine/commit/5d246cd54d4b6ab2810dcac8fc91ad9325c7ba94)
and the paired workflow's source summary identify the exact tested sources.
The workflow rebuilds that backend and runs the production BlueGenes browser
and account checks against it. It provides service version 36 and the deferred
OAuth transaction API.

Local validation of the browser transaction and request-ownership changes:

| Check | Result |
| --- | --- |
| Public Ring handler and HTTP fixture | 30 tests, 176 assertions passed |
| Full ClojureScript suite | 127 tests, 484 assertions passed |
| Production browser navigation and login/logout | 2 tests passed |
| Paired account, list, preference and isolation API checks | 2 tests passed |
| Alliance tools | 89 passed; 2 optional live-mine checks skipped (fixture unreachable) |
| Clojure formatting | All source files passed |
| Aislop | 100; no errors or warnings; Clojure semantics unsupported |

The production JavaScript and CSS were compiled and served with their
fingerprinted manifests. The test accounts were disposable. Existing local mine
data remained unchanged: snapshots of eight authenticated and two public lists,
including their members, were byte-identical before and after these checks.

OAuth tests use local provider/InterMine fixtures; they do not establish a live
Google or other provider login. Production deployment still requires the explicit
public URL, provider callback registration and process affinity described in
[configuration](configuring.md#oauth-and-session-deployment).
The generic Employee test model validates authentication and list behavior;
biological reports and tools need a separate genomic fixture.

The final review reproduced and corrected retired-cookie resurrection, cancellation
across rotation, namespace-only credential persistence, premature OAuth token
revocation during account logout, duplicate Java transaction expiry, portal
session replacement, persistent private assets, stale asset authorization errors,
false cleanup confirmation and credential exposure during validation/errors.
The Java core suite passed 2,287 tests and 26 deployed API/browser smoke checks.

The review leaves one minor UI issue: a delayed OAuth initiation can navigate the
tab back to its originating mine after a mine switch, although identity ownership
remains bound to the originating root and request. Live providers and multi-process
operation still require deployment validation. The existing anonymous-profile
merger does not establish crash-atomic recovery across multiple saved lists.
