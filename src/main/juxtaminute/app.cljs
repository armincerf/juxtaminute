(ns juxtaminute.app
  (:require [re-graph.core :as re-graph]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [reagent.dom :as rdom]))

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

(re-graph/init {:ws {:url "wss://cute-ape-95.hasura.app/v1/graphql"}
                :http {:url "https://cute-ape-95.hasura.app/v1/graphql"}})

(rf/reg-event-db
 ::success
 (fn [db [_ k res]]
   (assoc-in db [:data k] (:data res))))

(rf/reg-sub
 ::data
 (fn [db [_ k]]
   (get-in db [:data k])))

(defn root-view
  []
  (let [data @(rf/subscribe [::data :questions])]
    [:<>
     [:button {:on-click #(rf/dispatch [::re-graph/subscribe
                                        :questions
                                        questions-query
                                        {}
                                        [::success :questions]])}
      "get questions"]
     [:div.questions
      (for [q (:questions data)]
        ^{:key q}
        [:div.question
         [:h3 (:question q)]
         [:p (:answer q)]])]]))


(defn ^:export ^:dev/after-load render []
  (rdom/render [root-view]
                  (js/document.getElementById "root")))

(defn ^:export init []
  (rf/dispatch-sync [:initialize])
  (render))
