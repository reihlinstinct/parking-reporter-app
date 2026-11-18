# CI VPN API login proof (inactive, issue #597)

`api-vpn-login-proof.yml` is hard-disabled. It is not an operational route, cannot receive protected job secrets or initiate a tunnel while disabled, and has no submission action. Activation and actual diagnostic execution require their own authority and exact-head review.

The scaffold builds the existing JVM production-client classpath before private configuration, installs the existing transport dependencies before the tunnel, then calls the existing bounded L2TP transport. The transport refuses municipal routes outside ppp42 and blocks IPv6 HTTPS bypass. The workflow checks the municipal DNS/routes again immediately before using login credentials. Failure never falls back to direct egress. The existing live concurrency group prevents overlap with the current UI route. No settings, environment reviewer policy or permission changes are included.

`ApiVpnLoginProbe` accepts no report, contact, image, endpoint or operation argument. It constructs the production client with the existing credential names and calls only Login once. It returns fixed markers, never the token, response, URL or exception. Tests use synthetic fake clients and MockWebServer, including 403 refusal with exactly one request and no write. The workflow always invokes transport cleanup and uploads no private diagnostics.

## Limits

- Login success proves authentication through that network at that time. It does not prove CreateCase or AddFile passes the municipal edge, nor that a real report's topic/address is valid.
- This slice does not call the API batch controller, inject reporter identity, fetch private report bytes, write a journal or file a case. It is intentionally safe to review independently while those gates are completed.
- No live tunnel, login or municipal write was executed during development. The source's hard-disabled condition is not a substitute for checking protected environment reviewers before eventual activation.
- A separate approved supervised CreateCase and attachment proof is needed before claiming write egress. Case uncertainty stops the workflow and holds the incident; it is never a reason to retry.
- All old/new writers still need a single durable duplicate fence before batch activation. Missing key/seed/history/authority is a blocker, not something a successful login fixes.

The JVM path does not need Android screen driving. If it remains blocked despite VPN, stop and report the exact failure. Do not substitute browser/emulator automation under this route's label.

## Activation review limits

The existing protected submission environment is reused only as an inactive
scaffold name. It is NOT a read-only credential boundary: municipal credentials
can have write powers even when this caller only invokes Login. Before activating
this diagnostic workflow, verify whether the municipality supports scoped
read-only credentials and isolate the login-only environment/secret grant. If it
does not, record that limitation and obtain approval for the precise diagnostic
scope; do not call broad credentials read-only. No environment, credential or
review-policy changes are authorized or made by this PR.

DNS route checking and the client's later independent DNS resolution have a
TOCTOU gap. The authoritative egress boundary is the already-installed firewall:
IPv4 HTTPS must leave on ppp42 and IPv6 HTTPS is rejected. Route checks are extra
validation, not DNS pinning. Any firewall install failure aborts tunnel setup.
The existing subject-catalog query-token contract is not used here; do not change
the vendor API contract to an unverified header or log URLs in future callers.

The Ubuntu apt packages are not version-pinned and tunnel worst-case time has not
been measured. The job timeout bounds the attempt; it does not promise completion.
Activation review must decide transport-package reproducibility and establish the
actual runtime budget. Static workflow assertions do not prove firewall behavior
on a real runner. Synthetic client tests and successful CI establish code behavior,
not a live network security audit or a successful municipal login.
