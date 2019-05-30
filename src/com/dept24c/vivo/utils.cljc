(ns com.dept24c.vivo.utils
  (:require
   #?(:cljs [cljs.reader :as reader])
   #?(:clj [clojure.edn :as edn])
   #?(:cljs [clojure.pprint :as pprint])
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.capsule.client :as cc]
   [deercreeklabs.lancaster :as l]
   [deercreeklabs.stockroom :as sr]
   #?(:clj [puget.printer :refer [cprint]]))
  #?(:cljs
     (:require-macros
      [com.dept24c.vivo.utils :refer [sym-map]])))

(defmacro sym-map
  "Builds a map from symbols.
   Symbol names are turned into keywords and become the map's keys.
   Symbol values become the map's values.
  (let [a 1
        b 2]
    (sym-map a b))  =>  {:a 1 :b 2}"
  [& syms]
  (zipmap (map keyword syms) syms))

(defn pprint [x]
  #?(:clj (cprint x)
     :cljs (pprint/pprint x)))

(defn ex-msg [e]
  #?(:clj (.toString ^Exception e)
     :cljs (.-message e)))

(defn ex-stacktrace [e]
  #?(:clj (clojure.string/join "\n" (map str (.getStackTrace ^Exception e)))
     :cljs (.-stack e)))

(defn ex-msg-and-stacktrace [e]
  (str "\nException:\n" (ex-msg e) "\nStacktrace:\n" (ex-stacktrace e)))

(defn current-time-ms []
  #?(:clj (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

(defn edn->str [edn]
  (pr-str edn))

(defn str->edn [s]
  #?(:clj (edn/read-string s)
     :cljs (reader/read-string s)))

;;;;;;;;;;;;;;;;;;;; Schemas ;;;;;;;;;;;;;;;;;;;;

(def valid-ops
  #{:set :remove :insert-before :insert-after
    :plus :minus :multiply :divide :mod})

(def op-schema (l/enum-schema :com.dept24c.vivo.utils/op
                              {:key-ns-type :none} (seq valid-ops)))
(def store-id-schema l/string-schema)

(l/def-record-schema skeyword-schema
  [:ns (l/maybe l/string-schema)]
  [:name l/string-schema])

(l/def-union-schema spath-item-schema
  skeyword-schema
  l/string-schema
  l/int-schema)

(l/def-array-schema spath-schema
  spath-item-schema)

(defn long->non-neg-str [l]
  #?(:cljs (if (.isNegative l)
             (str "1" (.toString (.negate l)))
             (str "0" (.toString l)))
     :clj (if (neg? l)
            (str "1" (Long/toString (* -1 l) 10))
            (str "0" (Long/toString l 10)))))

(defn schema->value-rec-name [value-schema]
  (str "v-" (long->non-neg-str (l/fingerprint64 value-schema))))

(defn make-value-rec-schema [value-schema]
  (l/record-schema (keyword "com.dept24c.vivo.utils"
                            (schema->value-rec-name value-schema))
                   [[:v value-schema]]))

(defn make-values-union-schema [state-schema]
  (l/union-schema (map make-value-rec-schema (l/sub-schemas state-schema))))

(defn make-update-cmd-schema [state-schema]
  (l/record-schema ::update-cmd
                   [[:path spath-schema]
                    [:op op-schema]
                    [:arg (l/maybe (make-values-union-schema state-schema))]]))

(defn make-update-state-arg-schema [state-schema]
  (let [update-cmd-schema (make-update-cmd-schema state-schema)]
    (l/record-schema ::update-state-arg
                     [[:tx-info-str (l/maybe l/string-schema)]
                      [:update-cmds (l/array-schema update-cmd-schema)]])))

(l/def-record-schema get-state-arg-schema
  [:store-id (l/maybe store-id-schema)]
  [:path spath-schema])

(l/def-record-schema store-change-schema
  [:store-id store-id-schema]
  [:tx-info-str l/string-schema])

(l/def-record-schema connect-store-arg-schema
  [:branch l/string-schema]
  [:schema-pcf l/string-schema])

(l/def-record-schema login-subject-arg-schema
  [:id l/string-schema]
  [:secret l/string-schema])

(defn make-sm-server-protocol [state-schema]
  (let [values-union-schema (make-values-union-schema state-schema)
        get-state-ret-schema (l/record-schema ::get-state-ret-schema
                                              [[:store-id store-id-schema]
                                               [:value values-union-schema]])]
    {:roles [:state-manager :server]
     :msgs {:update-state {:arg (make-update-state-arg-schema state-schema)
                           :ret (l/maybe store-change-schema)
                           :sender :state-manager}
            :get-state {:arg get-state-arg-schema
                        :ret get-state-ret-schema
                        :sender :state-manager}
            :connect-store {:arg connect-store-arg-schema
                            :ret l/boolean-schema
                            :sender :state-manager}
            :login-subject {:arg login-subject-arg-schema
                            :ret l/boolean-schema
                            :sender :state-manager}
            :store-changed {:arg store-change-schema
                            :sender :server}}}))

(def schema-at-path (sr/memoize-sr l/schema-at-path 100))

(defn value-rec-key [state-schema path]
  (let [value-schema (schema-at-path state-schema path)
        rec-name (schema->value-rec-name value-schema)]
    (keyword rec-name "v")))

(defn edn->value-rec [state-schema path v]
  (let [k (value-rec-key state-schema path)]
    {k v}))

(defn value-rec->edn [state-schema path value-rec]
  (get value-rec (value-rec-key state-schema path)))

(defn kw->skeyword [kw]
  #:skeyword{:ns (namespace kw)
             :name (name kw)})

(defn skeyword->kw [skw]
  (let [{:skeyword/keys [ns name]} skw]
    (keyword ns name)))

(defn path->spath [path]
  (reduce (fn [acc k]
            (conj acc (if (keyword? k)
                        (kw->skeyword k)
                        k)))
          [] path))

(defn spath->path [spath]
  (reduce (fn [acc k]
            (conj acc (if (map? k)
                        (skeyword->kw k)
                        k)))
          [] spath))


;;;;;;;;;;;;;;;;;;;; Platform detection ;;;;;;;;;;;;;;;;;;;;

(defn jvm? []
  #?(:clj true
     :cljs false))

(defn browser? []
  #?(:clj false
     :cljs (exists? js/navigator)))

(defn node? []
  #?(:clj false
     :cljs (boolean (= "nodejs" cljs.core/*target*))))

(defn platform-kw []
  (cond
    (jvm?) :jvm
    (node?) :node
    (browser?) :browser
    :else :unknown))
