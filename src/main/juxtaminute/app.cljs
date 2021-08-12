(ns juxtaminute.app
  (:require [re-graph.core :as re-graph]
            [tick.alpha.api :as tick]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [medley.core :as medley]
            [reagent.dom :as rdom]
            [juxtaminute.common :as common]
            [clojure.string :as str]))
(js/console.log "start")
(when-let [stuff (some-> js/document
                         (.getElementById "userInfo")
                         (.getAttribute "content"))]
  (do (def me (js->clj (.parse js/JSON stuff)
                       :keywordize-keys true))
      (def my-name (or  (:nickname me) (:displayName me)))))

(def regraph-config {:ws {:url "wss://cute-ape-95.hasura.app/v1/graphql"
                          :supported-operations #{:subscribe}}
                     :http {:url "https://cute-ape-95.hasura.app/v1/graphql"}})

(def game-users-query
  "{
  game_users {
    user {
      name
      total_score
      votes {
        voter {
          name
          id
        }
      }
      id
    }
    chosen
    score
  }
}")

(def users-query
  "{
  users {
    id
  }
}")

(def topics-query
  "{
  game_topics {
    name
votes {
        voter {
          id
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
    ended
    topic
    id
    challenge
    created
    userByWinner {
      name
    }
    challengerUser {
      name
      id
      game_users {
        score
      }
    }
  }
}")

(defn button
  [opts text]
  [:button
   (merge
    {:disabled @(rf/subscribe [::loading?])}
    opts)
   text])

(defn all-votes
  [k vals]
  (->> vals
       (map (if k (comp :votes k) :votes))
       (apply concat)
       (map (comp :id :voter))))

(defn chosen-user
  [users]
  (:user (medley/find-first :chosen users)))

(defn current-state
  []
  (let [users @(rf/subscribe [::users])
        voted-user @(rf/subscribe [::voted-user])
        round @(rf/subscribe [::round])]
    (if (some #(= (:id me) (get-in % [:user :id])) users)
      (cond
        (= false (:ended round))
        :round
        (some? voted-user)
        :topic-voting
        :else
        :waiting-room)
      :join-game)))


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

(def start-round-mutation
  "mutation start_round($id: Int!, $ends_at: timestamptz) {
  update_game_round_by_pk(pk_columns: {id: $id}, _set: {ends_at: $ends_at}) {
    id
  }
}
")

(def round-won-mutation
  "mutation round_won($id: Int!, $winner: String) {
  update_game_round_by_pk(pk_columns: {id: $id}, _set: {winner: $winner}) {
    id
  }
}
")

(def reset-vote-mutation
  "mutation reset_votes {
  delete_vote(where: {}) {
    affected_rows
  }
}")

(def end-round-mutation
  "mutation end_round($id: Int!, $user_id: String!) {
  update_game_round_by_pk(pk_columns: {id: $id}, _set: {ended: true}) {
    id
  }
  delete_vote(where: {}) {
    affected_rows
  }
  update_game_users_by_pk(pk_columns: {user_id: $user_id}, _set: {chosen: false}) {
    user_id
  }
  update_game_users(where: {score: {_neq: 0}}, _set: {score: 0}) {
    affected_rows
  }
}")

(def quit-game-mutation
  "mutation quit_game($user_id: String!) {
  delete_game_users_by_pk(user_id: $user_id) {
    user_id
  }
}
")

(def join-new-game-mutation
  "mutation join_game($user_id: String!, $id: String!, $name: String) {
  insert_users(objects: {id: $id, name: $name}) {
    affected_rows
  }
  insert_game_users_one(object: {user_id: $user_id}) {
    user_id
  }
}
")

(def join-game-mutation
  "mutation join_game($user_id: String!) {
  insert_game_users_one(object: {user_id: $user_id}) {
    user_id
  }
}
")

(def challenge-mutation
  "mutation challenge($id: Int!, $challenge: String, $challenger: String) {
  update_game_round_by_pk(pk_columns: {id: $id}, _set: {challenge: $challenge, challenger: $challenger}) {
    id
  }
}
")

(def challenge-agree-mutation
  "mutation challenge_agree($user_id: String!, $score: Int, $id: Int!, $ends_at: timestamptz, $_eq: String) {
  update_game_users(where: {user_id: {_eq: $_eq}}, _set: {chosen: false}) {
    affected_rows
  }
  update_game_users_by_pk(pk_columns: {user_id: $user_id}, _set: {chosen: true, score: $score}, _inc: {}) {
    user_id
  }
  update_game_round_by_pk(pk_columns: {id: $id}, _set: {ends_at: $ends_at, challenger: null, challenge: null, chosen_user: $user_id}) {
    id
  }
}")

(def challenge-disagree-mutation
  "mutation challenge_disagree($user_id: String!, $score: Int, $id: Int!, $ends_at: timestamptz) {
  update_game_users_by_pk(pk_columns: {user_id: $user_id}, _set: {score: $score}) {
    user_id
  }
  update_game_round_by_pk(pk_columns: {id: $id}, _set: {ends_at: $ends_at, challenger: null, challenge: null}) {
    id
  }
}")

(rf/reg-event-fx
 ::vote-for-topic
 (fn [{:keys [db]} [_ topic]]
   (let [deciding-vote? (<= 1 (count (:votes topic)))
         chosen-user (and deciding-vote? (chosen-user (get-in db [:data :game-users :game_users])))]
     (prn (:votes topic) "hi")
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
   (prn user "votes" (count (:votes user)))
   (let [deciding-vote? (<= 1 (count (:votes user)))]
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
 ::db
 (fn [db]
   db))

(rf/reg-sub
 ::loading?
 (fn [db]
   (seq (get-in db [:re-graph :re-graph.internals/default :http :requests]))))

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
        voted? (some #{(:id me)} (all-votes nil topics))]
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
          [button
           {:on-click #(rf/dispatch [::vote-for-topic topic])}
           "Vote"])])]))

(defn waiting-room
  []
  (let [users @(rf/subscribe [::users])
        voted? (some #{(:id me)} (all-votes :user users))]
    [:div.users
     [common/pprint-code users]
     (if voted?
       [:p "Thanks for voting, waiting for others..."]
       [:p "Please vote for who you want to speak"])
     (for [{:keys [user]} users
           :let [votes (:votes user)
                 voter-names (map #(get-in % [:voter :name]) votes)
                 me? (= (:id me) (:id user))]]
       ^{:key user}
       [:div.user
        [:h3 (str "User - " (:name user) (when me? " (you)"))]
        (when (seq voter-names)
          [:p (str "Votes so far - " (str/join ", " voter-names))])
        (when-not (or voted? me?)
          [button
           {:on-click #(rf/dispatch [::vote-for-user user])}
           "Vote"])])]))

(defn countdown
  [round]
  (r/with-let [updater (r/atom 130)
               timer-fn (js/setInterval #(swap! updater dec) 500)]
    (let [end-time (tick/instant (tick/parse (:ends_at round)))
          duration (tick/duration
                    {:tick/beginning (tick/instant)
                     :tick/end end-time})
          hours (tick/hours duration)
          minutes (tick/minutes (tick/- duration
                                        (tick/new-duration hours :hours)))
          seconds (tick/seconds (tick/- duration
                                        (tick/new-duration minutes :minutes)
                                        (tick/new-duration hours :hours)))
          ;;stupid but not sure if theres a better way
          _ @updater]
      (cond
        (>= minutes 1)
        [:p.countdown (str "Get Ready! Starting in " seconds " seconds")]
        (tick/< (tick/instant) end-time)
        [:<>
         [:p "Topic is " (:topic round)]
         [:p.timer (str seconds " seconds remaining! (reduced timer for test)")]
         (if (= (:id me) (:chosen_user round))
           [:p "SPEAK!!"]
           (for [challenge ["Hesitation" "Deviation" "Repetition"]]
             ^{:key challenge}
             [button
              {:on-click #(rf/dispatch [::re-graph/mutate
                                        :challenge
                                        challenge-mutation
                                        {:id (:id round)
                                         :challenge challenge
                                         :challenger (:id me)}
                                        [:challenge-success]])}
              (str challenge "!")]))]
        :else
        (if (nil? (:userByWinner round))
          (rf/dispatch [::re-graph/mutate
                        :won-round
                        round-won-mutation
                        {:id (:id round)
                         :winner (:chosen_user round)}
                        [:won-round-success]]))
        (js/clearInterval timer-fn)
        "Time's up!"))
    (finally (js/clearInterval timer-fn))))

(defn timer-view
  [round]
  [:<>
   [countdown round]])

(defn challenge-view
  [round]
  (let [user (:challengerUser round)
        end-time (tick/instant (tick/parse (:ends_at round)))
        duration (tick/duration
                  {:tick/beginning (tick/instant)
                   :tick/end end-time})
        seconds-left (tick/seconds duration)
        voted-user-id (:id @(rf/subscribe [::voted-user]))]
    [:div
     [:h2 "Challenge!"]
     [:p (str (:name user) " has submitted the challange - " (:challenge round))]
     (when-not (or (= (:id me)
                      (:id user))
                   (= (:id me)
                      voted-user-id))
       [:div.buttons
        [button
         {:on-click #(rf/dispatch [::re-graph/mutate
                                   :challenge-agree
                                   challenge-agree-mutation
                                   {:score (inc (:score (first (:game_users user))))
                                    :user_id (:id user)
                                    :id (:id round)
                                    :_eq voted-user-id
                                    :ends_at (common/add (+ 2 seconds-left)
                                                         {:duration :seconds})}
                                   [:challenge-agree-success]])}
         "Agree"]
        [button
         {:on-click #(rf/dispatch [::re-graph/mutate
                                   :challenge-disagree
                                   challenge-disagree-mutation
                                   {:score (dec (:score (first (:game_users user))))
                                    :user_id (:id user)
                                    :id (:id round)
                                    :ends_at (common/add (+ 2 seconds-left)
                                                         {:duration :seconds})}
                                   [:challenge-disagree-success]])}
         "Disagree"]])]))



(defn round-over
  [round]
  (let [users (:game_users @(rf/subscribe [::data :game-users]))]
    [:div
     [:div "Round over! The winner was "
      (get-in round [:userByWinner :name])]
     [:div.scores
      "scores: "
      (str/replace (str/join ", " (map (juxt (comp :name :user) :score) users))
                   #"\[|\]|" "")]
     [button
      {:on-click #(rf/dispatch [::re-graph/mutate
                                :new-round
                                end-round-mutation
                                {:id (:id round)
                                 :user_id (or (:id @(rf/subscribe [::voted-user]))
                                              (:id me))}
                                [:new-round-success]])}
      "New Round"]
     [button
      {:on-click #(do
                    (prn "quit")
                    (rf/dispatch [::re-graph/mutate
                                  :quit
                                  quit-game-mutation
                                  {:user_id (:id me)}
                                  [:quit-success]]))}
      "Quit game"]]))

(defn round-view
  []
  (let [{:keys [topic userByWinner ends_at challenge] :as round}
        @(rf/subscribe [::round])
        user @(rf/subscribe [::voted-user])]
    [:<>
     [common/pprint-code round]
     (cond
       (some? userByWinner)
       [round-over round]
       (some? challenge)
       [challenge-view round]
       (some? ends_at)
       [timer-view round]
       :else
       [:div
        (if (= (:id user) (:id me))
          [:<>
           [:h3 (str (:name user) ", are you ready to start?")]
           [button

            {:on-click #(rf/dispatch [::re-graph/mutate
                                      :start-round
                                      start-round-mutation
                                      {:id (:id round)
                                       :ends_at (common/add 65 {:duration :seconds})}
                                      []])}
            "Lets go!"]]
          [:p "waiting for " (:name user)])])]))

(rf/reg-event-fx
 :generic-success
 (fn [_ _]
   (prn "success")))

(rf/reg-event-fx
 ::join-game
 (fn [_ [_ resp]]
   (prn resp)
   (let [users (get-in resp [:data :users])
         exists? (some #(= (:id me) (:id %)) users)]
     (prn exists?)
     {:fx [[:dispatch [::re-graph/mutate
                       :join-game
                       (if exists?
                         join-game-mutation
                         join-new-game-mutation)
                       (if exists?
                         {:user_id (:id me)}
                         {:user_id (:id me)
                          :id (:id me)
                          :name my-name})
                       [:generic-success]]]]})))

(defn join-game
  []
  [:div
   [:h3 "Not in the game"]
   [button
    {:on-click #(rf/dispatch [::re-graph/query
                              users-query
                              {}
                              [::join-game]])}
    "Join now"]])

(defn root-view
  []
  (if my-name
    (let [state (current-state)]
      [:<>
                                        ;[common/pprint-code @(rf/subscribe [::db])]
       [:p "you are logged in as " my-name]
       (case state
         :join-game [join-game]
         :round [round-view]
         :topic-voting [topic-vote]
         :waiting-room [waiting-room])])
    [:p "loading..."]))


(defn ^:export ^:dev/after-load render []
  (rdom/render [root-view]
                  (js/document.getElementById "root")))

(defn ^:export init []
  (rf/dispatch-sync [::init])
  (render))
