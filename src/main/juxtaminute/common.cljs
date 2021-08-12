(ns juxtaminute.common
  (:require
   [tick.alpha.api :as tick]
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.pprint :as pprint]))

(defn pprint-str
  [x]
  (with-out-str (pprint/pprint x)))

(defn pprint-code
  [x]
  (when false
    [:code
     {:style {:text-align "left"}}
     [:pre (pprint-str x)]]))

(defn add
  "Add duration unit (defaults to minutes) to an instant (now, by default).
  Duration units available are :millis :seconds :minutes :hours :days Returns
  `java.util.Date.`"
  ([minutes] (add minutes {:now (tick/instant) :duration :minutes}))
  ([amount {:keys [now duration] :or {duration :minutes}}]
   (->> duration
        (tick/new-duration amount)
        (tick/+ (or now (tick/instant))))))

(defn subtract
  "Subtract duration unit (defaults to minutes) from an instant (now, by default).
  Duration units available are :millis :seconds :minutes :hours :days Returns
  `java.util.Date.`"
  ([minutes] (subtract minutes {:now (tick/instant) :duration :minutes}))
  ([amount {:keys [now duration] :or {duration :minutes}}]
   (->> duration
        (tick/new-duration amount)
        (tick/- (or now (tick/instant))))))
