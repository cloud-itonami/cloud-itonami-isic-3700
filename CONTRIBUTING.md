# Contributing to cloud-itonami-isic-3700

Contributions should preserve the actor's scope: back-office coordination only,
with CRITICAL exclusions of direct pump/valve-equipment control and
public-health-authority discharge decisions (see README.md).

- All code must be .cljc (portable Clojure, no JVM-only constructs).
- Tests must pass: clojure -M:test
- Commit messages should link to relevant ADRs or issues.

**This actor does NOT:**
- Direct pump/valve-equipment control (actuation).
- Public-health-authority discharge decisions (discharge-permit issuance,
  boil-water-notice issuance, effluent-discharge authorization).

Contributions that cross these boundaries will be rejected.
