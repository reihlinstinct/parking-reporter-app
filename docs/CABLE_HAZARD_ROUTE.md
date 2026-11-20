# Narrow charging-cable sidewalk hazard route

This extends only the one-case submission route. Existing parking validation is
unchanged. It does not claim that a cable hazard is illegal parking and does not
append any parking or legal boilerplate.

A cable package uses the existing numbered-address/photo schema plus:
- `eventType`: `charging_cable_sidewalk_hazard`
- `subjectCode`: `5164`
- `subjectPath`: `["כבישים מדרכות", "אחר"]`

The existing `plate` field is an identifier in the exact supplied description,
not a parking allegation. It remains a 7-8 digit string; preserve leading zeros.
This narrow first version requires it and does not admit arbitrary other hazards.

Dispatch confirmation is `FILE_ONE_APPROVED_CABLE_HAZARD`, distinct from
`FILE_ONE_APPROVED_CASE`. The confirmation selects a separate validation mode in
both Python and the JVM bridge. Cross-mode packets fail closed. Merely adding
5164 to an ordinary parking package is refused.

The catalog path above is a candidate from the captured catalog, not proof of
current availability. After Login, before contact registration, claim or Create,
the bridge reads the live catalog and requires code5164, name Other, and the
exact Roads/Sidewalks path. A missing, renamed or differently parented category
stops the run without creating a case. Other5164 paths, including parking, do not
satisfy this check. This verifies catalog availability only, not case routing.
Code 5164 is shared by multiple catalog rows, including parking/Other. The
production Create contract sends only `CaseSubjectCode`; it has no verified
category-path field. `subjectPath` is an internal preflight condition and is
not transmitted. The city decides routing for code-only 5164, and this route
has not verified which bucket receives the case. Do not promise a specific
bucket when reporting the result. The approved hazard text remains unchanged.

Exact approved text, address and photo must still be supplied by the executor.
There is no automatic wording or claim about capture time, cable height, live
wires, vehicle registry status or parking legality. The original package and
outgoing photo digests, owner-bound dispatch, expiry, green-source check,
exclusive writer fence, mandatory VPN, pre-create claim, accepted-receipt
persistence, one attachment and encrypted diagnostics are unchanged. An accepted
or uncertain Create is never retried.

Code review or merge alone authorizes no filing. The operator must recover the
specific owner's report request and any necessary clarification before dispatch.
The live preflight is verification, not permission.
