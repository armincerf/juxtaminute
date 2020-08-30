(ns juxtaminute.app
  (:require [re-graph.core :as re-graph]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [reagent.dom :as rdom]
            [juxtaminute.common :as common]))

(def regraph-config {:ws {:url "wss://cute-ape-95.hasura.app/v1/graphql"}
                     :http {:url "https://cute-ape-95.hasura.app/v1/graphql"}})

(def questions-query
  (str
   "{"
   "questions(order_by: {askedTime: desc}) {
    user
    question
    id
    askedTime
    answer
  }"
   "}"))

(def game-state-query
  (str
   "{"
   "questions(order_by: {askedTime: desc}) {
    user
    question
    id
    askedTime
    answer
  }"
   "}"))

(rf/reg-event-db
 ::success
 (fn [db [_ k res]]
   (assoc-in db [:data k] (:data res))))

(rf/reg-event-fx
 ::init
 (fn [_ _]
   {:fx [[:dispatch [::re-graph/init regraph-config]]
         [:dispatch [::re-graph/subscribe
                     :game-state
                     game-state-query
                     [::success :game-state]]]]}))

(rf/reg-sub
 ::data
 (fn [db [_ k]]
   (get-in db [:data k])))

(defn root-view
  []
  (let [data @(rf/subscribe [::data :game-state])]
    [common/pprint-code data]))


(defn ^:export ^:dev/after-load render []
  (rdom/render [root-view]
                  (js/document.getElementById "root")))

(defn ^:export init []
  (rf/dispatch-sync [::init])
  (render))
