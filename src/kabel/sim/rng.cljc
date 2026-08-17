(ns kabel.sim.rng
  "A seeded pseudo-random generator that produces **identical** streams on the
  JVM and in ClojureScript.

  ## Why not the host's RNG

  `clojure.core/rand`, `java.util.Random` and `js/Math.random` all disagree
  across platforms, and the first two cannot be seeded portably. A simulator
  whose randomness differs per platform is not reproducible, and a bug that
  only appears under one seed on one platform is the hardest kind to find.

  ## Why xorshift128

  It uses **only shifts and xors** — no multiplication. That is the whole
  reason it was chosen. ClojureScript numbers are doubles, so a 32-bit
  multiply silently loses precision above 2^53, and `Math.imul` has no direct
  JVM counterpart; every multiply is a place the two platforms could diverge.
  Shifts and xors, normalised through `u32` after each step, do not have that
  problem.

  This generator is for *simulation*, not for keys. It is not
  cryptographically secure and must never be used where unpredictability
  matters — see `kabel.identity` for that.

  ## Purely functional

  Every function takes an rng value and returns `[rng' result]`. Nothing here
  mutates, so a simulation can be forked, replayed or rewound by keeping the
  value — which is what makes a failing seed reproducible."
  (:refer-clojure :exclude [shuffle rand-int rand-nth]))

;; =============================================================================
;; 32-bit arithmetic that agrees on both platforms
;; =============================================================================

(defn u32
  "Normalise `x` to an unsigned 32-bit value.

  This is the single most important function in the namespace. On the JVM a
  long masked with 0xFFFFFFFF is already the unsigned value; in ClojureScript
  `bit-and` coerces to **32-bit signed**, so `(bit-and 0xFFFFFFFF 0xFFFFFFFF)`
  is `-1` rather than `4294967295`. `unsigned-bit-shift-right` compiles to
  JavaScript's `>>>`, whose ToUint32 conversion gives the unsigned reading."
  [x]
  #?(:clj (bit-and x 0xFFFFFFFF)
     :cljs (unsigned-bit-shift-right x 0)))

(defn- xor-shift-left [x n] (u32 (bit-xor x (u32 (bit-shift-left x n)))))
(defn- xor-shift-right [x n] (u32 (bit-xor x (unsigned-bit-shift-right (u32 x) n))))

;; =============================================================================
;; Construction
;; =============================================================================

(def ^:private default-state
  ;; Any non-zero state will do; the all-zero state is a fixed point of
  ;; xorshift and would emit nothing but zeros forever.
  [0x9E3779B9 0x85EBCA6B 0xC2B2AE35 0x27D4EB2F])

(defn- step
  "Advance the xorshift128 state by one. Returns the new state."
  [{:keys [w0 w1 w2 w3]}]
  (let [t (xor-shift-right (xor-shift-left w0 11) 8)
        w3' (u32 (bit-xor (xor-shift-right w3 19) t))]
    {:w0 w1 :w1 w2 :w2 w3 :w3 w3'}))

(defn make-rng
  "An rng seeded from an integer.

  The seed is spread across four words and the generator is warmed up, so
  neighbouring seeds (0, 1, 2 …) produce unrelated streams rather than
  streams that start out similar — which matters when tests sweep seeds."
  [seed]
  (let [s (u32 seed)
        [a b c d] default-state
        state {:w0 (u32 (bit-xor s a))
               :w1 (u32 (bit-xor (u32 (bit-shift-left s 7)) b))
               :w2 (u32 (bit-xor (unsigned-bit-shift-right s 3) c))
               :w3 (u32 (bit-xor s d))}
        state (if (= 0 (:w0 state) (:w1 state) (:w2 state) (:w3 state))
                (zipmap [:w0 :w1 :w2 :w3] default-state)
                state)]
    ;; Warm-up: discard 16 outputs so the seed's structure is not visible in
    ;; the first values drawn.
    (nth (iterate step state) 16)))

;; =============================================================================
;; Core step
;; =============================================================================

(defn next-u32
  "Draw the next unsigned 32-bit value. Returns `[rng' v]`."
  [rng]
  (let [rng' (step rng)]
    [rng' (:w3 rng')]))

(def ^:private two32 4294967296)

(defn next-double
  "Draw a double in `[0, 1)`. Returns `[rng' v]`."
  [rng]
  (let [[rng' v] (next-u32 rng)]
    [rng' (/ (double v) (double two32))]))

(defn rand-int
  "Draw an integer in `[0, n)`. Returns `[rng' v]`.

  Uses rejection sampling rather than `mod`, because `mod` biases towards the
  low values whenever `n` does not divide 2^32 — a bias that is invisible in
  small tests and skews peer selection in large ones."
  [rng n]
  (when (or (nil? n) (<= n 0))
    (throw (ex-info "rand-int needs a positive bound"
                    {:type :kabel.sim.rng/bad-bound :n n})))
  (let [limit (* n (quot two32 n))]
    (loop [rng rng
           guard 0]
      (let [[rng' v] (next-u32 rng)]
        (cond
          (< v limit) [rng' (mod v n)]
          ;; The rejection probability is below 1/2 per draw, so 128 rejections
          ;; in a row means something is structurally wrong rather than
          ;; unlucky. Fail loudly instead of looping forever.
          (> guard 128) (throw (ex-info "rand-int failed to converge"
                                        {:type :kabel.sim.rng/no-convergence :n n}))
          :else (recur rng' (inc guard)))))))

(defn rand-range
  "Draw an integer in `[lo, hi]` inclusive. Returns `[rng' v]`."
  [rng lo hi]
  (if (= lo hi)
    [rng lo]
    (let [[rng' v] (rand-int rng (inc (- hi lo)))]
      [rng' (+ lo v)])))

(defn rand-bool
  "Draw `true` with probability `p`. Returns `[rng' v]`."
  [rng p]
  (let [[rng' v] (next-double rng)]
    [rng' (< v p)]))

;; =============================================================================
;; Collection operations
;; =============================================================================

(defn shuffle
  "Uniformly shuffle `coll`. Returns `[rng' vector]`.

  Fisher-Yates, walking from the end and swapping with a uniformly chosen
  index in `[0, i]`.

  This function is written out rather than delegated because getting it subtly
  wrong is a documented failure mode: partisan's `shuffle/2` selects the
  **first** K peers and only reorders the result, so its peer sampling is not
  random at all and no test noticed
  (see `.internal/reference/partisan.md`). `kabel.sim.rng-test` asserts the
  property that catches it — that every element can reach every position."
  [rng coll]
  (let [v (vec coll)
        n (count v)]
    (if (<= n 1)
      [rng v]
      (loop [rng rng
             v v
             i (dec n)]
        (if (zero? i)
          [rng v]
          (let [[rng' j] (rand-int rng (inc i))]
            (recur rng'
                   (assoc v i (nth v j) j (nth v i))
                   (dec i))))))))

(defn sample
  "Draw `k` distinct elements of `coll` uniformly, without replacement.

  Returns `[rng' vector]`, of length `min(k, (count coll))` — asking for more
  than exists yields everything rather than an error, because that is what a
  peer-selection call site wants when the network is small."
  [rng k coll]
  (let [[rng' shuffled] (shuffle rng coll)]
    [rng' (vec (take k shuffled))]))

(defn rand-nth
  "Draw one element of a non-empty `coll`. Returns `[rng' element]`."
  [rng coll]
  (let [v (vec coll)]
    (when (empty? v)
      (throw (ex-info "rand-nth on an empty collection"
                      {:type :kabel.sim.rng/empty-collection})))
    (let [[rng' i] (rand-int rng (count v))]
      [rng' (nth v i)])))

(defn weighted-choice
  "Draw one key from `weights`, a map of `item -> non-negative weight`.

  Returns `[rng' item]`. Keys are visited in a deterministic order, so the
  result depends only on the rng and the map's contents — never on hash
  iteration order, which differs between platforms."
  [rng weights]
  (let [entries (sort-by (comp str key) weights)
        total (reduce + 0 (map val entries))]
    (when (<= total 0)
      (throw (ex-info "weighted-choice needs a positive total weight"
                      {:type :kabel.sim.rng/bad-weights :weights weights})))
    (let [[rng' r] (next-double rng)
          target (* r total)]
      [rng' (loop [acc 0
                   [[item w] & more] entries]
              (let [acc' (+ acc w)]
                (if (or (< target acc') (empty? more))
                  item
                  (recur acc' more))))])))
