(ns juxtaminute.app
  (:require [re-graph.core :as re-graph]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [medley.core :as medley]
            [reagent.dom :as rdom]
            [juxtaminute.common :as common]
            [clojure.string :as str]))

(def me {:id  "1"
         :name "test1"})

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

(def game-users-query
  "{
  game_users {
    user {
      name
      votes {
        voter {
          name
        }
      }
      id
    }
    chosen
    score
  }
}")

(def topics-query
  "{
  game_topics {
    name
votes {
        voter {
          name
        }
      }
  }
}")

(def round-query
  "{
  game_round(limit: 1, order_by: {created: desc}) {
    chosen_user
    ends_at
    topic
    id
    challenger
    challenge
    created
  }
}")

(defn all-votes
  [k vals]
  (->> vals
       (map (if k (comp :votes k) :votes))
       (apply concat)
       (map (comp :name :voter))))

(defn chosen-user
  [users]
  (:user (medley/find-first :chosen users)))

(defn current-state
  []
  (let [users @(rf/subscribe [::users])
        voted-user @(rf/subscribe [::voted-user])
        round @(rf/subscribe [::round])]
    (cond
      (some? round)
      :round
      (some? voted-user)
      :topic-voting
      :else
      :waiting-room)))


(rf/reg-event-db
 ::success
 (fn [db [_ k res]]
   (assoc-in db [:data k] (:data res))))

(rf/reg-event-fx
 ::init
 (fn [_ _]
   {:fx [[:dispatch [::re-graph/init regraph-config]]
         [:dispatch [::re-graph/subscribe
                     :game-users
                     game-users-query
                     {}
                     [::success :game-users]]]
         [:dispatch [::re-graph/subscribe
                     :game-topics
                     topics-query
                     {}
                     [::success :game-topics]]]
         [:dispatch [::re-graph/subscribe
                     :game-round
                     round-query
                     {}
                     [::success :game-round]]]]}))

(def vote-for-user-mutation
  "mutation vote_for_user($voter_id: String, $voted_user: String) {
  insert_vote_one(object: {voted_user: $voted_user, voter_id: $voter_id}) {
    id
  }
  }
")

(def vote-for-topic-mutation
  "mutation vote_for_topic($voter_id: String, $voted_topic: String) {
  insert_vote_one(object: {voter_id: $voter_id, voted_topic: $voted_topic}) {
    id
  }
}")

(def choose-user-mutation
  "mutation choose_user($user_id: String!) {
  update_game_users_by_pk(pk_columns: {user_id: $user_id}, _set: {chosen: true}) {
    user_id
  }
}
")

(def create-round-mutation
  "mutation start_round($chosen_user: String, $topic: String)
 {
 insert_game_round_one(object: {chosen_user: $chosen_user, topic: $topic}) {
 id
 }
 }")

(rf/reg-event-fx
 ::vote-for-topic
 (fn [{:keys [db]} [_ topic]]
   (let [deciding-vote? (= 1 (count (:votes topic)))
         chosen-user (and deciding-vote? (chosen-user (get-in db [:data :game-users :game_users])))]
     {:fx [[:dispatch [::re-graph/mutate
                       :vote-for-topic
                       vote-for-topic-mutation
                       {:voted_topic (:name topic)
                        :voter_id (:id me)}
                       [::vote-for-topic-success]]]
           (when deciding-vote?
             [:dispatch [::re-graph/mutate
                         :create-round
                         create-round-mutation
                         {:chosen_user (:id chosen-user)
                          :topic (:name topic)}
                         []]])]})))


(rf/reg-event-fx
 ::vote-for-user
 (fn [_ [_ user]]
   (js/console.log user)
   (let [deciding-vote? (= 1 (count (:votes user)))]
     {:fx [[:dispatch [::re-graph/mutate
                       :vote-for-user
                       vote-for-user-mutation
                       {:voted_user (:id user)
                        :voter_id (:id me)}
                       [::vote-for-user-success]]]
           (when deciding-vote?
             [:dispatch [::re-graph/mutate
                         :choose-user
                         choose-user-mutation
                         {:user_id (:id user)}
                         []]])]})))

(rf/reg-sub
 ::data
 (fn [db [_ k]]
   (get-in db [:data k])))

(rf/reg-sub
 ::topics
 :<- [::data :game-topics]
 (fn [{:keys [game_topics]} _]
   game_topics))

(rf/reg-sub
 ::round
 :<- [::data :game-round]
 (fn [{:keys [game_round]} _]
   (first game_round)))

(rf/reg-sub
 ::users
 :<- [::data :game-users]
 (fn [{:keys [game_users]} _]
   game_users))

(rf/reg-sub
 ::voted-user
 :<- [::users]
 (fn [users _]
  (chosen-user users)))

(defn topic-vote
  []
  (let [topics @(rf/subscribe [::topics])
        voted? (some #{(:name me)} (all-votes nil topics))]
    [:div.topics
     [common/pprint-code topics]
     (for [{:keys [name] :as topic} topics
           :let [votes (:votes topic)
                 voter-names (map #(get-in % [:voter :name]) votes)]]
       ^{:key name}
       [:div.topic
        [:h3 (str "Topics - " name)]
        (when (seq voter-names)
          [:p (str "Votes so far - " (str/join ", " voter-names))])
        (when-not voted?
          [:button
           {:on-click #(rf/dispatch [::vote-for-topic topic])}
           "Vote"])])]))

(defn waiting-room
  []
  (let [users @(rf/subscribe [::users])
        voted? (some #{(:name me)} (all-votes :user users))]
    [:div.users
     [common/pprint-code users]
     (for [{:keys [user]} users
           :let [votes (:votes user)
                 voter-names (map #(get-in % [:voter :name]) votes)]]
       ^{:key user}
       [:div.user
        [:h3 (str "User - " (:name user))]
        (when (seq voter-names)
          [:p (str "Votes so far - " (str/join ", " voter-names))])
        (when-not voted?
          [:button
           {:on-click #(rf/dispatch [::vote-for-user user])}
           "Vote"])])]))

(defn timer-view
  [round]
  [:div "timer"])

(defn challenge
  [round]
  [:div "Challenge!"])

(defn round-view
  []
  (let [{:keys [topic ends_at challenge challenger] :as round}
        @(rf/subscribe [::round])
        user @(rf/subscribe [::voted-user])]
    [:<>
     [common/pprint-code round]
     (cond
       (some? challenge)
       [challenge round]
       (some? ends_at)
       [timer-view round]
       :else
       [:div
        [:h3 (str (:name user) ", are you ready to start?")]
        (if (= (:id user) (:id me))
          [:button "Lets go!"]
          [:p "waiting for " (:name user)])])]))

(defn root-view
  []
  (let [state (current-state)]
    [common/pprint-code (str "Current state = " state)]
    (case state
      :round [round-view]
      :topic-voting [topic-vote]
      :waiting-room [waiting-room])))


(defn ^:export ^:dev/after-load render []
  (rdom/render [root-view]
                  (js/document.getElementById "root")))

(defn ^:export init []
  (rf/dispatch-sync [::init])
  (render))
