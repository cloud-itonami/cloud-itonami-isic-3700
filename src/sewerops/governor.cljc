(ns sewerops.governor
  "SewerageOpsGovernor -- the independent compliance layer that earns
  the SewerOpsAdvisor the right to commit. The advisor has no notion of
  whether a sewer-system-segment/treatment-facility is actually
  registered and verified, whether its own proposed `:effect` secretly
  claims a direct actuation instead of a mere proposal, or whether it
  has silently drifted into a permanently out-of-scope decision area,
  so this MUST be a separate system able to *reject* a proposal and
  fall back to HOLD.

  This actor's scope is deliberately narrow -- OPERATIONS COORDINATION
  only (system-record logging, maintenance scheduling, safety-concern
  flagging, supply ordering). It NEVER performs or authorizes:
    - direct pump/valve-equipment control (actuation)
    - public-health-authority discharge decisions (discharge-permit
      issuance, boil-water-notice issuance, effluent-discharge
      authorization)

  Three HARD checks, ALL permanent, un-overridable by any human
  approval:

    1. Facility unverified         -- the target sewer-system-segment/
                                       treatment-facility record must
                                       exist AND be independently
                                       confirmed `:registered?`/
                                       `:verified?` in the store before
                                       ANY proposal for it may commit
                                       or even escalate. Never trusts a
                                       proposal's own claim about the
                                       facility -- re-derived from the
                                       facility's own store record, the
                                       same 'ground truth, not
                                       self-report' discipline every
                                       sibling actor's governor uses.
    2. Effect not :propose         -- every proposal's `:effect` MUST
                                       be `:propose`. Any other effect
                                       value is, by construction, a
                                       claim to directly actuate/commit
                                       outside governance -- HARD
                                       block, not merely
                                       low-confidence.
    3. Scope exclusion             -- ANY proposal (regardless of op)
                                       whose op, rationale, summary,
                                       citations or draft value touches
                                       pump/valve-equipment-control or
                                       public-health-authority
                                       discharge-decision territory is
                                       a HARD, PERMANENT block -- this
                                       actor's charter excludes that
                                       territory structurally, not as a
                                       rollout milestone. Evaluated
                                       UNCONDITIONALLY on every
                                       proposal, the same 'exercise the
                                       failure mode directly'
                                       discipline every sibling actor's
                                       own unconditional-evaluation
                                       checks establish. An op outside
                                       the closed four-op allowlist is
                                       the SAME failure mode (an
                                       advisor proposing something it
                                       was never authorized to propose)
                                       and is folded into this same
                                       check.

  Two ESCALATE (SOFT) gates, both always requiring human sign-off
  regardless of how clean the proposal otherwise is:

    - `:flag-safety-concern` -- ALWAYS escalates, regardless of
      confidence. `sewerops.phase` independently agrees:
      `:flag-safety-concern` is never a member of any phase's `:auto`
      set either -- two layers, not one.
    - `:order-supplies` above `supply-cost-threshold` -- a
      high-value procurement proposal ALWAYS escalates for human
      budget sign-off, even when governor-clean and high-confidence,
      REGARDLESS of rollout phase (an :order-supplies proposal that
      would otherwise auto-commit at phase 3 still escalates once its
      `:estimated-cost` clears the threshold). Below the threshold, a
      clean high-confidence order is treated like any other write.

  Plus the ordinary LLM-confidence-floor escalate every sibling
  actor's governor also applies."
  (:require [clojure.string :as str]
            [sewerops.store :as store]))

(def confidence-floor 0.6)

(def supply-cost-threshold
  "Above this (currency-unit-agnostic) :estimated-cost, an
  :order-supplies proposal always escalates for human budget sign-off,
  regardless of rollout phase or governor cleanliness otherwise."
  5000)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`)."
  #{:log-system-record :schedule-maintenance
    :flag-safety-concern :order-supplies})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- direct pump/valve-
  equipment control, or a public-health-authority discharge decision.
  Scanned across the proposal's op/summary/rationale/cites/value,
  never trusting the advisor's own framing of its intent."
  ["pump control" "pump-control" "ポンプ制御"
   "valve control" "valve-control" "バルブ制御"
   "pump actuation" "pump-actuation" "ポンプ作動"
   "valve actuation" "valve-actuation" "バルブ作動"
   "discharge authorization" "discharge-authorization" "放流許可"
   "discharge permit" "discharge-permit" "放流許可証"
   "public health order" "public-health order" "public-health-order" "公衆衛生命令"
   "boil water notice" "boil-water notice" "boil-water-notice" "煮沸勧告"
   "effluent discharge decision" "effluent-discharge-decision" "放流決定"])

;; ----------------------------- checks -----------------------------

(defn- facility-unverified-violations
  "The target facility must exist AND be independently
  `:registered?`/`:verified?` in the store -- never trust the
  proposal's own `:facility-id` claim without a store lookup."
  [{:keys [facility-id]} st]
  (let [f (store/facility st facility-id)]
    (when-not (and f (:registered? f) (:verified? f))
      [{:rule :facility-unverified
        :detail (str facility-id " は未登録または未検証のfacility -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content touches pump/valve-equipment-control or
  public-health-authority discharge-decision territory, regardless of
  confidence or how clean every other check is. Evaluated
  UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "ポンプ/バルブ設備の直接制御、または公衆衛生機関の放流判断領域に触れる提案は永久に禁止"}])))

(defn- high-cost-supply-order?
  "An :order-supplies proposal whose draft `:value` carries an
  `:estimated-cost` above `supply-cost-threshold`. Independent of the
  advisor's own confidence -- a high-value order always needs a human
  to sign off on the budget, not merely the technical content."
  [proposal]
  (boolean
   (and (= :order-supplies (:op proposal))
        (some-> proposal :value :estimated-cost (> supply-cost-threshold)))))

(defn check
  "Censors a SewerOpsAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [facility-id (or (:facility-id proposal) (:facility-id request))
        hard (into []
                   (concat (facility-unverified-violations {:facility-id facility-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        always-escalate? (boolean (always-escalate-ops (:op proposal)))
        high-cost? (high-cost-supply-order? proposal)
        stakes? (boolean (or always-escalate? high-cost?))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :facility-id (:facility-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
