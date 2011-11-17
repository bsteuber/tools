(ns com.contentjon.gen.core
  "Contains general parser functions and the definition of the parser
   monad, which are used in more specialized parsers."
  (:refer-clojure :exclude [+ * not or])
  (:use [clojure.contrib.monads]
        [com.contentjon.fn.algo :only (applier)]))

(defprotocol AsParser
  "this section defines what can be turned into a parser function
   and how this is done"
  (as-parser [_]))

(defn parser-t [m]
  (monad [m-result (with-monad m m-result)
          m-bind   (with-monad m
                     (fn m-bind-parser-t [p f]
                       (m-bind (as-parser p) f)))
          m-zero   (with-monad m m-zero)
          m-plus   (with-monad m
                     (fn m-plus-parser-t [& ps]
                       (apply m-plus (map as-parser ps))))]))

(def #^{:doc "The parser monad is implemented using (state-t maybe-m).
              nil is used as an error value that lets a parser fail.
              An additional monad transformer is used wrap monadic values
              in a call to as-parser, which allows to use a lot of literal
              values as parsers, such as string, vectors and maps"}
  parser-m
  (parser-t (state-t maybe-m)))

;;; macro for defining a parser

(defmacro parser
  "Creates a new monadic parser with the passed in bindings and body."
  ([bindings body]
    `(domonad parser-m ~bindings ~body)))

(defmacro defparser
  "Defines a new monadic parser with the passed in name,
   bindings and body."
  ([name bindings body]
     `(def ~name (parser ~bindings ~body))))

;;; helpers for calling parsers

(def #^{:doc "Takes the result of a parser and checks if it failed"}
  parser-fail? nil?)

(defn result
  "Return the result of a parse process"
  [in]
  (first in))

(defn parser-finished?
  "Checks if a parser has successfully consumed all of it's input"
  [in]
  (and (clojure.core/not (parser-fail? in))
       (empty? (second in))))

(defn parse
  "Uses a parser to parse the input and returns the result of the parsing
   process if the parser consumes all input"
  [rule in]
  (when-let [parsed (rule in)]
    (when (parser-finished? parsed)
      (first parsed))))

(defn parse-partial
  "Uses a parser to parse the input and returns a vector of parser
   result and unconsumed input"
  [rule in]
  (rule in))

;;; public matching functions

(defn lambda
  "The empty word. Returns a parser that matches nothing and returns the entire input state."
  []
  (fn [in] [nil in]))

(defn any
  "Returns a parser that matches the first input symbol with no further inspection
   and returns the input without it's first symbol as the new state"
  []
  (fn [in]
    (when-not (empty? in)
      [(first in) (rest in)])))

(defn of
  "Takes a predicate and returns a parser that matches the first symbol of the input if the
   predicate evaluates to a value that is not logically false"
  [predicate]
  (fn [in]
    (when (and (clojure.core/not (empty? in))
               (predicate (first in)))
      [(first in) (rest in)])))

(defn not
  "A parser that fails when p succeeds and succeeds when p fails"
  [p]
  (fn [in]
    (when-not ((as-parser p) in)
      [nil in])))

(def #^{:doc "Returns a parser that matches one of multiple alternatives"
	:arglists '([& rules]) }
     or (with-monad parser-m m-plus))

(def or* (applier or))

(defn ?
  "The parser may apply another parser or not"
  [rule]
  (or rule (lambda)))

(declare +)

(defn *
  "Returns a parser that applies a subparser 0 or more times to it's input"
  [rule]
  (? (+ rule)))

(defn +
  "Returns a parser that applies a subparser 1 or more times to it's input"
  [rule]
  (fn [in]
    (let [rule (as-parser rule)]
      (loop [unparsed in
             result   nil]
        (if-let [parsed (rule unparsed)]
          (if (first parsed)
            (recur (second parsed)
                   [(conj (vec (first result)) (first parsed)) (second parsed)])
            [(first result) unparsed])
          result)))))

(defn times
  "A parser that applies p between min and max times.
   If only min is given, the parser is applied exactly min times"
  ([p min]
     (times p min min))
  ([p min max]
     { :pre [(<= min max)]}
     (letfn [(next-parser [n]
               (if (= n max)
                 (lambda)
                 (let [next (parser [f p
                                     r (next-parser (inc n))]
                              (if f
                                (cons f r)))]
                   (if (< n min)
                     next
                     (? next)))))]
       (next-parser 0))))

;;; some useful parsers

(defn surround
  "A parser that first runs before, then p, then after and returns
   the result of p when all succeed"
  ([p around]
     (surround p around around))
  ([p before after]
     (parser [_ before
              res p
              _ after]
             res)))

(defn surrounder
  "Returns a function that transforms a parser with the
   surround function"
  ([around]
     (surrounder around around))
  ([before after]
     #(surround % before after)))

(defn aba
  "Similar to sourround but it returns the sourrunded
   element as well"
  [a b]
  (parser [before a
           mid    b
           after  a]
    [before mid after]))

(defn log
  "A debug parser that logs its input with an additional message"
  [p msg]
  (fn [in]
    (println "msg" in "=>")
    (let [result ((as-parser p) in)]
      (println "\t" result)
      result)))

(defn -g-> 
  "Takes a parser p and a generator function g. 
   Returns a parser that accepts the same input as p, but additionally 
   applies g to its result."
  [p g]
  (parser [res p]
    (g res)))

(defn replacer [p r]
  (-g-> p (constantly r)))

;;; types extended to be parsers

(defn assert-state
  "Returns a parser that matches nothing. It returns a nil value and its input as its state.
   Before it does this it applies a predicate to its input state and lets the parser fail
   if it doesn't match"
  [predicate]
  (parser [state (fetch-state)
	   _     #(if (predicate state) [% state] nil)]
	  nil))

(defn- without-match [match in]
  (.substring in (.length match) (.length in)))

(defn descend [rule]
  (parser [stream (fetch-state)
           :when  (clojure.core/not (empty? stream))
           :let   [f (first stream)]
           :when  (clojure.core/or (string? f) (isa? (class f) clojure.lang.Seqable))
           _      (set-state (if (string? f)
                               f
                               (seq (first stream))))
           result rule
           _      (assert-state empty?)
           _      (set-state (rest stream))]
    result))

(defn maybe-descend [rule]
  (fn [in]
    (if (sequential? in)
      ((descend rule) in)
      (rule in))))

(defmacro tree-parser [bindings body]
  `(descend (parser ~bindings ~body)))

(defmacro deftree-parser [n bindings body]
  `(def ~n (tree-parser ~bindings ~body)))

(extend-protocol AsParser
  java.lang.Character
  (as-parser [this]
    (fn [in]
      (let [length (.length in)]
        (when (and (> length 0)
                   (= (.charAt in 0) this))
          [this (.substring in 1 length)]))))
  java.lang.String
  (as-parser [this]
    (maybe-descend
     (fn [in]
       (when (and  (string? in)
                   (.startsWith in this))
         [this (without-match this in)]))))
  java.util.regex.Pattern
  (as-parser [this]
    (maybe-descend
     (fn [in]
       (let [pat (java.util.regex.Pattern/compile (str "^" this ""))
             m   (.matcher pat in)]
         (when (and (string? in) (.find m))
           (let [match (.group m)]
             [match (without-match match in)]))))))
  clojure.lang.IPersistentVector
  (as-parser [this]
    (let [rule (* (apply or this))]
      (descend rule)))
  clojure.lang.IPersistentMap
  (as-parser [this]
    (descend
     (parser [pairs (+ (descend
                        (parser [k (of this)
                                 v (get this k)]
                          [k v])))]
             (into {} pairs))))
  clojure.lang.Fn
  (as-parser [this] this)
  java.lang.Object
  (as-parser [this]
    (throw (java.lang.IllegalArgumentException. (str "Not a supported parser type: " this))))
  nil
  (as-parser [_]
    (throw (java.lang.IllegalArgumentException. "Not a supported parser type: nil"))))
