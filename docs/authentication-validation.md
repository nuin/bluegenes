# Java 21 authentication validation

The paired CI workflow uses InterMine commit
`fde56493d32054f2fa97b5dacdbddeaeac46685c`, which passed its
[core and deployed-client workflow](https://github.com/nuin/intermine/actions/runs/45298956306)
and [style gate](https://github.com/nuin/intermine/actions/runs/45298956447).
It provides service version 36 and the deferred OAuth transaction API.

Local validation of the browser transaction and request-ownership changes:

| Check | Result |
| --- | --- |
| Public Ring handler and HTTP fixture | 25 tests, 129 assertions passed |
| Full ClojureScript suite | 118 tests, 461 assertions passed |
| Production browser navigation and login/logout | 2 tests passed |
| Paired account, list, preference and isolation API checks | 2 tests passed |
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
