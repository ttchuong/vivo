(ns com.dept24c.integration.integration-test
  (:require
   [clojure.core.async :as ca]
   [clojure.test :refer [deftest is]]
   [com.dept24c.vivo :as vivo]
   [com.dept24c.vivo.state-schema :as ss]
   [com.dept24c.vivo.utils :as u]
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.capsule.logging :as logging])
  #?(:clj
     (:import
      (clojure.lang ExceptionInfo))))

(defn get-server-url []
  "ws://localhost:12345/state-manager")

(def sm-opts {:get-server-url get-server-url
              :sys-state-schema ss/state-schema
              :sys-state-store-name "vivo-test"
              :sys-state-store-branch "integration-test"})

(def user-bo #:user{:name "Bo Johnson"
                    :nickname "Bo"})
(def user-bo-id 1)

(defn configure-logging []
  (logging/add-log-reporter! :println logging/println-reporter)
  (logging/set-log-level! :debug))

;;(configure-logging)

(defn join-msgs-and-users [msgs users]
  (reduce (fn [acc {:msg/keys [user-id text] :as msg}]
            (if-not msg
              acc
              (conj acc {:user (users user-id)
                         :text text})))
          [] msgs))

(deftest test-subscriptions
  (au/test-async
   10000
   (ca/go
     (let [sm (vivo/state-manager sm-opts)]
       (try
         (let [msg #:msg{:user-id user-bo-id
                         :text "A msg"}
               msg2 (assoc msg :msg/text "This is great")
               last-msg-ch (ca/chan 1)
               all-msgs-ch (ca/chan 1)
               app-name-ch (ca/chan 1)
               index-ch (ca/chan 1)
               expected-all-msgs [{:text "A msg"
                                   :user {:user/name "Bo Johnson"
                                          :user/nickname "Bo"}}
                                  {:text "This is great"
                                   :user {:user/name "Bo Johnson"
                                          :user/nickname "Bo"}}]]
           (is (= true (au/<? (vivo/<update-state!
                               sm [{:path [:sys :state/msgs]
                                    :op :set
                                    :arg []}
                                   {:path [:sys :state/users]
                                    :op :set
                                    :arg {user-bo-id user-bo}}
                                   {:path [:sys :state/msgs -1]
                                    :op :insert-after
                                    :arg msg}
                                   {:path [:sys :state/msgs -1]
                                    :op :insert-after
                                    :arg msg2}]))))
           (vivo/subscribe! sm "test-sub-app-name"
                            '{app-name [:sys :state/app-name]}
                            (fn [df]
                              (if-let [app-name (df 'app-name)]
                                (ca/put! app-name-ch app-name)
                                (ca/close! app-name-ch))))
           (vivo/subscribe! sm "test-sub-all-msgs"
                            '{msgs [:sys :state/msgs]
                              users [:sys :state/users]}
                            (fn [{:syms [msgs users]}]
                              (if (seq msgs)
                                (let [msgs* (join-msgs-and-users msgs users)]
                                  (ca/put! all-msgs-ch msgs*))
                                (ca/close! all-msgs-ch))))
           (vivo/subscribe! sm "test-sub-index"
                            '{uid->msgs [:sys :state/user-id-to-msgs]}
                            (fn [{:syms [uid->msgs]}]
                              (if (seq uid->msgs)
                                (ca/put! index-ch uid->msgs)
                                (ca/close! index-ch))))
           (vivo/subscribe! sm "test-sub-last-msg"
                            '{last-msg [:sys :state/msgs -1]}
                            (fn [df]
                              (if-let [last-msg (df 'last-msg)]
                                (ca/put! last-msg-ch last-msg)
                                (ca/close! last-msg-ch))))
           (is (= "test-app" (au/<? app-name-ch)))
           (is (= msg2 (au/<? last-msg-ch)))
           (is (= expected-all-msgs (au/<? all-msgs-ch)))
           (is (= {1 [{:msg/text "This is great" :msg/user-id 1}
                      {:msg/text "A msg" :msg/user-id 1}]}
                  (au/<? index-ch)))
           (au/<? (vivo/<update-state! sm [{:path [:sys :state/msgs -1]
                                            :op :remove
                                            :arg msg}]))
           (is (= msg (au/<? last-msg-ch)))
           (is (= 1 (count (au/<? all-msgs-ch))))
           (is (= {1 [{:msg/text "A msg" :msg/user-id 1}]}
                  (au/<? index-ch)))
           (vivo/unsubscribe! sm "test-sub-app-name")
           (vivo/unsubscribe! sm "test-sub-all-msgs")
           (vivo/unsubscribe! sm "test-sub-last-msg"))
         (finally
           (vivo/shutdown! sm)))))))

(deftest test-authentication
  (au/test-async
   10000
   (ca/go
     (let [sm (vivo/state-manager sm-opts)]
       (try
         (let [df-ch (ca/chan)]
           (vivo/subscribe! sm "test-auth"
                            '{subject-id [:local :vivo/subject-id]}
                            (fn [df]
                              (ca/put! df-ch df)))
           (is (= {'subject-id nil} (au/<? df-ch)))
           (vivo/log-in! sm "x" "x")
           (is (= {'subject-id "user-a"} (au/<? df-ch)))
           (vivo/log-out! sm)
           (is (= {'subject-id nil} (au/<? df-ch)))
           (vivo/unsubscribe! sm "test-auth"))
         (finally
           (vivo/shutdown! sm)))))))

(deftest test-authorization
  (au/test-async
   10000
   (ca/go
     (let [sm (vivo/state-manager sm-opts)]
       (try
         (let [df-ch (ca/chan)
               expected-df {'app-name "test-app"
                            'secret :vivo/unauthorized}]
           (vivo/subscribe! sm "test-authz"
                            '{app-name [:sys :state/app-name]
                              secret [:sys :secret]}
                            (fn [df]
                              (ca/put! df-ch df)))
           (is (= expected-df (au/<? df-ch)))
           (vivo/unsubscribe! sm "test-authz"))
         (finally
           (vivo/shutdown! sm)))))))
