(ns status-im.data-store.visibility-status-updates
  (:require [clojure.set :as clojure.set]
            [status-im.ethereum.json-rpc :as json-rpc]
            [status-im.utils.fx :as fx]
            [taoensso.timbre :as log]))

(defn <-rpc [visibility-status-update]
  (clojure.set/rename-keys visibility-status-update {:publicKey  :public-key
                                                     :statusType :status-type}))

(fx/defn fetch-visibility-status-updates-rpc
  [_ on-success]
  {::json-rpc/call [{:method     "wakuext_statusUpdates"
                     :params     []
                     :on-success #(on-success (:statusUpdates ^js %))
                     :on-failure #(log/error
                                   "failed to fetch visibility-status-updates" %)}]})
