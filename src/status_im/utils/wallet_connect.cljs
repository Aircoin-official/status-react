(ns status-im.utils.wallet-connect
  (:require ["@walletconnect/client" :default WalletConnectClient]
            ;; ["@walletconnect/types" :default WalletConnectTypes]
            ["@react-native-community/async-storage" :default AsyncStorage]))

(def default-relay-provider "wss://relay.walletconnect.com")

(defn init [on-success on-error]
  (-> ^js WalletConnectClient
      (.init (clj->js {:controller true
                       :apiKey "c4f79cc821944d9680842e34466bfbd"
                       :relayProvider default-relay-provider
                       :metadata {:name "Status Wallet"
                                  :description "Status is a secure messaging app, crypto wallet, and Web3 browser built with state of the art technology."
                                  :url "#"
                                  :icons ["https://statusnetwork.com/img/press-kit-status-logo.svg"]}
                       :storageOptions {:asyncStorage AsyncStorage}}))
      (.then on-success)
      (.catch on-error)))