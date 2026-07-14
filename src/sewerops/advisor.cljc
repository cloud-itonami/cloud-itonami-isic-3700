(ns sewerops.advisor
  "SewerOpsAdvisor -- the *contained intelligence node* for the
  ISIC-3700 municipal-sewerage operations-coordination actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: system-record logging (flow-rate/inspection/maintenance
  data), maintenance scheduling (pipe-inspection/pump-maintenance/
  cleaning), safety-concern flagging (overflow/contamination/public-
  health), and supply ordering (equipment/chemical-treatment
  procurement). CRITICAL: it is a smart-but-untrusted advisor. It
  returns a *proposal* (with a rationale + the fields it cited), never
  a committed record and NEVER a direct actuation -- every proposal's
  `:effect` is always `:propose`. Every output is censored downstream
  by `sewerops.governor` before anything touches the SSoT.

  This advisor NEVER drafts pump/valve-equipment control (direct
  actuation) or any public-health-authority discharge decision
  (discharge-permit issuance, boil-water-notice issuance, effluent-
  discharge authorization) -- those are permanently out of scope for
  this actor, not merely un-implemented. `sewerops.governor`'s
  `scope-exclusion-violations` independently re-scans every proposal
  for exactly this failure mode (a compromised or confused advisor
  drifting into scope it must never touch) and HARD-holds it,
  regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op          kw             ; echoes the request op
     :facility-id str
     :summary     str            ; human-facing draft / finding
     :rationale   str            ; why -- SCANNED by the scope-exclusion gate
     :cites       [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect      :propose       ; ALWAYS :propose -- never a direct actuation
     :value       map            ; the draft payload a human/system would review
                                  ; (:order-supplies carries :estimated-cost, if
                                  ; supplied, so the governor can apply its
                                  ; cost-threshold escalation independently)
     :confidence  0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.model :as model]))

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-system-record
  "Draft a flow-rate/inspection/maintenance system-record log entry.
  Pure logging of ALREADY-OBSERVED data -- never a decision about pump
  or valve operation."
  [_db {:keys [facility-id patch]}]
  {:op          :log-system-record
   :facility-id facility-id
   :summary     (str facility-id " のシステム記録(流量/点検/保守データ)を提案: " (pr-str (keys patch)))
   :rationale   "入力された流量/点検データの記録提案のみ。新規事実の生成なし。"
   :cites       [facility-id]
   :effect      :propose
   :value       (merge {:facility-id facility-id} patch)
   :confidence  0.93})

(defn- propose-maintenance
  "Draft a pipe-inspection/pump-maintenance/cleaning scheduling
  proposal (a calendar entry/work order draft, never a direct
  dispatch or pump/valve actuation)."
  [_db {:keys [facility-id patch]}]
  {:op          :schedule-maintenance
   :facility-id facility-id
   :summary     (str facility-id " の管路点検/ポンプ保守/清掃予定を提案: " (pr-str (keys patch)))
   :rationale   "配管点検・ポンプ保守スケジュールの提案のみ。実際の保守作業実施の判断は人間が行う。"
   :cites       [facility-id]
   :effect      :propose
   :value       (merge {:facility-id facility-id} patch)
   :confidence  0.88})

(defn- propose-safety-concern
  "Surface an overflow/contamination/public-health concern for HUMAN
  triage. This op ALWAYS escalates in `sewerops.governor` -- never
  auto-committed at any phase (`sewerops.phase`) -- regardless of how
  confident the advisor is that the concern is real or minor. The
  advisor itself makes NO public-health determination; it only
  surfaces the observation."
  [_db {:keys [facility-id patch]}]
  {:op          :flag-safety-concern
   :facility-id facility-id
   :summary     (str facility-id " の公衆衛生上の懸念(溢水/汚染)を提起: " (pr-str (keys patch)))
   :rationale   "観測された懸念事象の提起のみ。公衆衛生評価・是正措置の決定は行わない -- 常に人間審査が必要。"
   :cites       [facility-id]
   :effect      :propose
   :value       (merge {:facility-id facility-id} patch)
   :confidence  (get patch :confidence 0.9)})

(defn- propose-supplies
  "Draft an equipment/chemical-treatment procurement proposal
  (purchase-requisition draft) -- coordination only, never the actual
  purchase-order approval or fund transfer itself. Carries an
  `:estimated-cost` (when supplied in `patch`) so the governor's
  cost-threshold check can independently escalate high-value orders."
  [_db {:keys [facility-id patch]}]
  {:op          :order-supplies
   :facility-id facility-id
   :summary     (str facility-id " の資材/薬剤処理用品の調達を提案: " (pr-str (keys patch)))
   :rationale   "設備・薬剤処理用品の調達提案のみ。実際の発注承認は人間が行う。"
   :cites       [facility-id]
   :effect      :propose
   :value       (merge {:facility-id facility-id} patch)
   :confidence  0.87})

(defn- propose-out-of-scope
  "Test/failure-mode hook: drafts a proposal that touches a
  permanently-excluded scope area (pump/valve-equipment control or a
  public-health-authority discharge decision) so the governor's
  `scope-exclusion-violations` HARD block can be exercised directly,
  the same 'exercise the failure mode directly' discipline every
  sibling actor's own sim/test suite uses. Never reachable from the
  closed op allowlist in normal operation -- only via the
  `:out-of-scope?` request flag."
  [_db {:keys [facility-id patch]}]
  {:op          :schedule-maintenance
   :facility-id facility-id
   :summary     (str facility-id " のポンプ制御バルブ作動シーケンスの変更を提案")
   :rationale   "対象facilityのpump controlとvalve actuationのタイミングを調整済み"
   :cites       [facility-id]
   :effect      :propose
   :value       (merge {:facility-id facility-id} patch)
   :confidence  0.9})

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :facility-id str :patch map ...}"
  [db {:keys [op out-of-scope?] :as request}]
  (cond
    out-of-scope?                 (propose-out-of-scope db request)
    (= op :log-system-record)     (propose-system-record db request)
    (= op :schedule-maintenance)  (propose-maintenance db request)
    (= op :flag-safety-concern)   (propose-safety-concern db request)
    (= op :order-supplies)        (propose-supplies db request)
    :else {:op op :facility-id (:facility-id request)
           :summary "未対応の操作" :rationale (str "closed allowlist に無い操作: " op)
           :cites [] :effect :propose :value {} :confidence 0.0}))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

;; ----------------------------- real-LLM advisor (production seam) -----------------------------

(def ^:private system-prompt
  (str "あなたは下水道事業の運営コーディネーション助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "許可された操作は :log-system-record / :schedule-maintenance / "
       ":flag-safety-concern / :order-supplies の4つのみです。"
       "ポンプ・バルブ設備の直接制御や、公衆衛生機関の判断"
       "(放流許可発行/煮沸勧告発令/放流決定)には絶対に触れてはいけません。"
       "キー: :op :facility-id :summary :rationale :cites :effect(常に :propose) "
       ":value :confidence(0..1)。"))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the governor escalates/holds --
  an LLM hiccup can never bypass governance."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :propose)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :propose :value {} :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ _st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n facility: " (:facility-id req)
                                              "\n patch: " (pr-str (:patch req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :advisor-proposal
   :op         (:op request)
   :facility-id (:facility-id request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
