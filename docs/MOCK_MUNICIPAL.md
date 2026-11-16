# Mock municipal 106 API

`tools/mock_municipal/server.py` is a standard-library Python server that mimics the submission path of the municipal 106 API, so the app and its tests can run against something that can fail on demand. It never contacts the real city, keeps nothing on disk, and answers with synthetic data only (tokens `mock-token-N`, case ids `mock-case-N`, case numbers with the `0000-` prefix).

Endpoints under `/CRMCityConnect/api/jerusalem/`, as used by `MunicipalClient.kt`: `POST Login`, `createupdatecontact`, `CreateCase`, `AddFileToCase`, and `GET GetSubjectCategories?token=`. A request without the `Ocp-Apim-Subscription-Key` header gets 401.

Run: `python3 tools/mock_municipal/server.py --port 8106 --scenario ok`.

Scenarios: `ok`, `rate_limit` (429 plus `Retry-After`), `server_error` (500), `slow` (`--delay` seconds), `bad_json`, `bad_success` (`"Success":"true"` as a string), `failure` (`Success:false`), `duplicate` (assumed behaviour), `redirect` (302), `drop` (connection closed with no answer). Change at runtime with `POST /__mock/scenario {"mode","endpoint","count","delay"}`; `GET /__mock/stats` returns per-endpoint request counts, which is how a test proves the app did not retry; `POST /__mock/reset`.

What is assumed, not known from the real API: the duplicate answer, the exact catalog fields and the 401 body. Keep the mock a stable stand-in; do not treat it as the city's specification. Used by the load-test work in #522.
