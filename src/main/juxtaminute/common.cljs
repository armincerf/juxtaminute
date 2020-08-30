(ns juxtaminute.common
  (:require
   [clojure.pprint :as pprint]))

(defn pprint-str
  [x]
  (with-out-str (pprint/pprint x)))

(defn pprint-code
  [x]
  [:code
   {:style {:text-align "left"}}
   [:pre (pprint-str x)]])

