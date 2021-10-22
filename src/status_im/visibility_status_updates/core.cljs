(ns status-im.visibility-status-updates.core
  (:require [status-im.data-store.visibility-status-updates :as visibility-status-updates-store]
            [status-im.ethereum.json-rpc :as json-rpc]
            [status-im.utils.fx :as fx]
            [status-im.constants :as constants]
            [status-im.multiaccounts.update.core :as multiaccounts.update]
            [status-im.utils.datetime :as datetime]))

(defn valid-status-type? [status-type]
  (some #(= status-type %)
        (list constants/visibility-status-always-online
              constants/visibility-status-inactive
              constants/visibility-status-automatic)))

(fx/defn load-visibility-status-updates
  {:events [:visibility-status-updates/visibility-status-updates-loaded]}
  [{:keys [db]} visibility-status-updates-loaded]
  (let [{:keys [visibility-status-updates]}
        (reduce (fn [acc visibility-status-update-loaded]
                  (let [{:keys [public-key] :as visibility-status-update}
                        (visibility-status-updates-store/<-rpc
                         visibility-status-update-loaded)]
                    (assoc-in acc [:visibility-status-updates public-key]
                              visibility-status-update)))
                {} visibility-status-updates-loaded)]
    {:db (assoc db :visibility-status-updates visibility-status-updates)}))

(defn handle-my-visibility-status-updates
  [acc my-current-status clock visibility-status-update]
  (let [status-type (:status-type visibility-status-update)]
    (if (and (valid-status-type? status-type)
             (or
              (nil? my-current-status)
              (> clock (:clock my-current-status))))
      (-> acc
          (update :current-user-visibility-status
                  merge {:clock clock :status-type status-type})
          (assoc :dispatch [:visibility-status-updates/send-visibility-status-updates?
                            (not= status-type constants/visibility-status-inactive)]))
      acc)))

(defn handle-other-visibility-status-updates
  [acc public-key clock visibility-status-update]
  (let [status-type (:status-type visibility-status-update)
        visibility-status-update-old
        (get-in acc [:visibility-status-updates public-key])]
    (if (and (valid-status-type? status-type)
             (or
              (nil? visibility-status-update-old)
              (> clock (:clock visibility-status-update-old))))
      (assoc-in acc [:visibility-status-updates public-key]
                visibility-status-update)
      acc)))

(fx/defn handle-visibility-status-updates
  [{:keys [db]} visibility-status-updates-received]
  (let [visibility-status-updates-old (if (nil? (:visibility-status-updates db)) {}
                                          (:visibility-status-updates db))
        my-public-key                 (get-in
                                       db [:multiaccount :public-key])
        my-current-status             (get-in
                                       db [:multiaccount :current-user-visibility-status])
        {:keys [visibility-status-updates current-user-visibility-status dispatch]}
        (reduce (fn [acc visibility-status-update-received]
                  (let [{:keys [public-key clock] :as visibility-status-update}
                        (visibility-status-updates-store/<-rpc
                         visibility-status-update-received)]
                    (if (= public-key my-public-key)
                      (handle-my-visibility-status-updates
                       acc my-current-status clock visibility-status-update)
                      (handle-other-visibility-status-updates
                       acc public-key clock visibility-status-update))))
                {:visibility-status-updates      visibility-status-updates-old
                 :current-user-visibility-status my-current-status}
                visibility-status-updates-received)]
    (merge {:db (-> db
                    (update-in [:visibility-status-updates]
                               merge visibility-status-updates)
                    (update-in [:multiaccount :current-user-visibility-status]
                               merge current-user-visibility-status))}
           (when dispatch {:dispatch dispatch}))))

(fx/defn visibility-status-option-pressed
  {:events [:visibility-status-updates/visibility-status-option-pressed]}
  [{:keys [db]} status-type]
  (let [events-to-dispatch-later
        (cond-> [{:ms 10 :dispatch
                  [:visibility-status-updates/update-visibility-status
                   status-type]}]
          (and
           (= status-type constants/visibility-status-inactive)
           (> (:peers-count db) 0))
          ;; Disable broadcasting further updates
          (conj {:ms 1000
                 :dispatch
                 [:visibility-status-updates/send-visibility-status-updates? false]}))]
    ;; Enable broadcasting for current broadcast
    {:dispatch        [:visibility-status-updates/send-visibility-status-updates? true]
     :dispatch-later  events-to-dispatch-later}))

(fx/defn update-visibility-status
  {:events [:visibility-status-updates/update-visibility-status]}
  [{:keys [db] :as cofx} status-type]
  {:db (update-in db [:multiaccount :current-user-visibility-status]
                  merge {:status-type status-type
                         :clock       (datetime/timestamp-sec)})
   ::json-rpc/call [{:method     "wakuext_setUserStatus"
                     :params     [status-type ""]
                     :on-success #()}]})

(fx/defn send-visibility-status-updates?
  {:events [:visibility-status-updates/send-visibility-status-updates?]}
  [cofx val]
  (multiaccounts.update/multiaccount-update cofx
                                            :send-status-updates? val
                                            {}))

(fx/defn timeout-user-online-status
  {:events [:visibility-status-updates/timeout-user-online-status]}
  [{:keys [db]} public-key clock]
  (let [current-clock (get-in db [:visibility-status-updates public-key :clock] 0)]
    (when (= current-clock clock)
      {:db (update-in db [:visibility-status-updates public-key]
                      merge {:status-type constants/visibility-status-inactive})})))

(fx/defn countdown-for-online-user
  {:events [:visibility-status-updates/countdown-for-online-user]}
  [{:keys [db]} public-key clock ms]
  {:dispatch-later
   [{:ms ms
     :dispatch [:visibility-status-updates/timeout-user-online-status public-key clock]}]})

(fx/defn delayed-visibility-status-update
  {:events [:visibility-status-updates/delayed-visibility-status-update]}
  [{:keys [db]} status-type]
  {:dispatch-later
   [{:ms 200
     :dispatch
     [:visibility-status-updates/visibility-status-option-pressed status-type]}]})

(fx/defn peers-summary-change
  [{:keys [db]} peers-count]
  (let [send-visibility-status-updates?
        (get-in db [:multiaccount :send-status-updates?])
        status-type
        (get-in db [:multiaccount :current-user-visibility-status :status-type])]
    (when (and
           (> peers-count 0)
           send-visibility-status-updates?
           (= status-type constants/visibility-status-inactive))
      {:dispatch [:visibility-status-updates/update-visibility-status status-type]
       :dispatch-later [{:ms 1000
                         :dispatch
                         [:visibility-status-updates/send-visibility-status-updates? false]}]
       :db (assoc-in db [:multiaccount :send-status-updates?] false)})))

(fx/defn sync-visibility-status-update
  [{:keys [db]} visibility-status-update-received]
  (let [my-current-status           (get-in db [:multiaccount :current-user-visibility-status])
        {:keys [status-type clock]} (visibility-status-updates-store/<-rpc
                                     visibility-status-update-received)]
    (when (and (valid-status-type? status-type)
               (or
                (nil? my-current-status)
                (> clock (:clock my-current-status))))
      {:db (update-in db [:multiaccount :current-user-visibility-status]
                      merge {:clock clock :status-type status-type})
       :dispatch [:visibility-status-updates/send-visibility-status-updates?
                  (not= status-type constants/visibility-status-inactive)]})))
