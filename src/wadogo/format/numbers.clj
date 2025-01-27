(ns wadogo.format.numbers
  (:require [clojure.pprint :refer [cl-format]]
            [clojure.string :refer [trim]]
            [fastmath.core :as m]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)
(m/use-primitive-operators)

;; maximum double power for precise calculations
(def ^:private ^:const ^long kp-max 22)

;; powers for scientific notation
(def ^:private tbl [1e-1,
                    1e00, 1e01, 1e02, 1e03, 1e04, 1e05, 1e06, 1e07, 1e08, 1e09,
                    1e10, 1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19,
                    1e20, 1e21, 1e22])

(defn- left
  "What is the power of number"
  ^long [^double x]
  (-> x m/log10 m/floor unchecked-long inc))

(defn- find-nsig
  "Shift decimal places until non-zero value is found"
  ^long [^double alpha ^long digits]
  (loop [a alpha
         d digits]
    (let [a- (/ a 10.0)]
      (if (= a- (m/floor a-))
        (recur a- (dec d))
        (max 1 d)))))

(defn- right
  "Calculate maximum digits on the right side of the dot."
  ^long [^double x ^long digits]
  (let [alpha (m/round (* x ^double (tbl (inc digits))))]
    (if (zero? alpha)
      1
      (find-nsig alpha digits))))

(defn- fix-left
  "Fix number of digits on the left side. For scientific notations and non-positive exponent (lft) it should be leading digits + sign."
  [^double x ^long lft e?]
  (let [sgn (if (neg? x) 1 0)]
    (if (or e? (not (pos? lft)))
      (+ sgn 1)
      (+ sgn lft))))

(defn- precision
  [^double x ^long digits ^long threshold]
  (if (zero? x)
    [false 0 1 1] ;; zero is reprezented as 0.0
    (let [digits (max 1 (min 10 digits)) ;; constrain digits to 1-10 range
          r (m/abs x)
          lft (left r) ;; digits on the left side of dot
          alft (m/abs lft)
          e? (>= alft threshold)
          r-prec (cond
                   (< alft threshold) r ;; normal number
                   (< alft kp-max) (if (neg? lft) ;; scientific number (using table to shift values)
                                     (* r ^double (tbl (inc (- lft))))
                                     (/ r ^double (tbl (inc lft))))
                   :else (/ r (m/pow 10.0 (dec lft)))) ;; very big or very small case
          rght (right r-prec digits) ;; desired precision on the right side
          exp (if (> alft 100) 3 2) ;; size of the exponent
          lft (fix-left x lft e?)]
      [e? exp lft rght])))

(defn- fit-precision
  "Find best matching presision for given sequence."
  [xs ^long digits ^long threshold]
  (reduce (fn [[ce? ^long cexp ^long clft ^long crght ^long non-finite-len] x]
            (let [^double x (if (instance? Float x)
                              (Double/valueOf (str x))
                              (or x ##NaN))]
              (if (Double/isFinite x)
                (let [[e? ^long exp ^long lft ^long rght] (precision x digits threshold)]
                  (if (and e? (pos? threshold))
                    (reduced (fit-precision xs digits 0)) ;; switch to scientific notation
                    [(or e? ce?)
                     (max exp cexp)
                     (max lft clft)
                     (max rght crght)
                     non-finite-len]))
                [ce? cexp clft crght (max non-finite-len (if (= x ##-Inf) 4 3))])))
          [false Integer/MIN_VALUE Integer/MIN_VALUE Integer/MIN_VALUE 0] xs))

;; public functions

(defn formatter
  "Create formatter for given:

  * `xs` - sequence of doubles
  * `digits` - maximum precision
  * `threshold` - what is absolute power to switch to scientific notation

  Returns formatter."
  ([xs] (formatter xs 8))
  ([xs ^long digits] (formatter xs digits 8))
  ([xs ^long digits ^long threshold] (formatter xs digits threshold false))
  ([xs ^long digits ^long threshold trim?]
   (let [[e? ^long exp ^long lft ^long rght ^long non-finite-len] (fit-precision xs digits threshold)
         w (max non-finite-len (if e?
                                 (+ lft rght exp 3) ;; 3 = "." + sign of E + "E"
                                 (+ lft rght 1))) ;; 1 for "."
         fmt (if e?
               (str "~" w "," rght "," exp "E")
               (str "~" w "," rght "F"))
         non-finite-fmt (str "~" w "@A")]
     (fn [x]
       (let [^double x (or x ##NaN)
             res (if (Double/isFinite x)
                   (cl-format nil fmt x)
                   (cl-format nil non-finite-fmt (cond
                                                   (== ##Inf x) "Inf"
                                                   (== ##-Inf x) "-Inf"
                                                   :else "NaN")))]
         (if trim? (trim res) res))))))

(defn format-sequence
  "Format sequence of double for given:

  * `xs` - sequence of doubles
  * `digits` - maximum precision
  * `threshold` - what is absolute power to switch to scientific notation

  Returns sequence of strings."
  ([xs] (format-sequence xs 8))
  ([xs ^long digits] (format-sequence xs digits 8))
  ([xs ^long digits ^long threshold] (format-sequence xs digits threshold false))
  ([xs ^long digits ^long threshold trim?]
   (let [fmt (formatter xs digits threshold trim?)]
     (map fmt xs))))
