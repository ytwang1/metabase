(ns metabase.api.database-test
  "Tests for /api/database endpoints."
  (:require [clojure
             [string :as str]
             [test :refer :all]]
            [medley.core :as m]
            [metabase
             [driver :as driver]
             [util :as u]]
            [metabase.api.database :as database-api]
            [metabase.driver.util :as driver.u]
            [metabase.mbql.schema :as mbql.s]
            [metabase.models
             [card :refer [Card]]
             [collection :refer [Collection]]
             [database :as database :refer [Database]]
             [field :refer [Field]]
             [field-values :refer [FieldValues]]
             [permissions :as perms]
             [permissions-group :as perms-group]
             [table :refer [Table]]]
            [metabase.sync
             [analyze :as analyze]
             [field-values :as field-values]
             [sync-metadata :as sync-metadata]]
            [metabase.test
             [data :as data]
             [fixtures :as fixtures]
             [util :as tu]]
            [metabase.test.data
             [env :as tx.env]
             [users :as test-users]]
            [metabase.test.util.log :as tu.log]
            [metabase.util.schema :as su]
            [schema.core :as s]
            [toucan.db :as db]
            [toucan.util.test :as tt]))

(use-fixtures :once (fixtures/initialize :plugins))

;; HELPER FNS

(driver/register! ::test-driver
                  :parent :sql-jdbc
                  :abstract? true)

(defmethod driver/connection-properties ::test-driver [_]
  nil)

(def ^:private default-db-details
  {:engine                      "h2"
   :name                        "test-data"
   :is_sample                   false
   :is_full_sync                true
   :is_on_demand                false
   :description                 nil
   :caveats                     nil
   :points_of_interest          nil
   :cache_field_values_schedule "0 50 0 * * ? *"
   :metadata_sync_schedule      "0 50 * * * ? *"
   :options                     nil
   :timezone                    nil
   :auto_run_queries            true})

(defn- db-details
  "Return default column values for a database (either the test database, via `(data/db)`, or optionally passed in)."
  ([]
   (db-details (data/db)))

  ([{driver :engine, :as db}]
   (merge
    default-db-details
    (select-keys db [:created_at :id :details :updated_at :timezone])
    {:features (map u/qualified-name (driver.u/features driver))})))


;; # DB LIFECYCLE ENDPOINTS

(defn- add-schedules [db]
  (assoc db :schedules {:cache_field_values {:schedule_day   nil
                                             :schedule_frame nil
                                             :schedule_hour  0
                                             :schedule_type  "daily"}
                        :metadata_sync      {:schedule_day   nil
                                             :schedule_frame nil
                                             :schedule_hour  nil
                                             :schedule_type  "hourly"}}))

;; ## GET /api/database/:id

(deftest regular-users--should-not--see-db-details
  (is (= (add-schedules (dissoc (db-details) :details))
         ((test-users/user->client :rasta) :get 200 (format "database/%d" (data/id))))))

(deftest superusers--should--see-db-details
  (is (= (add-schedules (db-details))
         ((test-users/user->client :crowberto) :get 200 (format "database/%d" (data/id))))))


;; ## POST /api/database
(defn- create-db-via-api! [& [m]]
  (let [db-name (tu/random-name)]
    (try
      (let [{db-id :id} (with-redefs [driver/available? (constantly true)]
                          ((test-users/user->client :crowberto) :post 200 "database"
                           (merge
                            {:name    db-name
                             :engine  (u/qualified-name ::test-driver)
                             :details {:db "my_db"}}
                            m)))]
        (assert (integer? db-id))
        (Database db-id))
      (finally (db/delete! Database :name db-name)))))

;; Check that we can create a Database
(tu/expect-schema
 (merge
  (m/map-vals s/eq default-db-details)
  {:created_at       java.time.temporal.Temporal
   :engine           (s/eq ::test-driver)
   :id               su/IntGreaterThanZero
   :details          (s/eq {:db "my_db"})
   :updated_at       java.time.temporal.Temporal
   :name             su/NonBlankString
   :features         (s/eq (driver.u/features ::test-driver))})
 (create-db-via-api!))

;; can we set `auto_run_queries` to `false` when we create the Database?
(deftest set-auto-run-queries-false
  (is (= {:auto_run_queries false}
         (select-keys (create-db-via-api! {:auto_run_queries false}) [:auto_run_queries]))))

;; can we set `is_full_sync` to `false` when we create the Database?
(deftest set-is-full-sync
  (is (= {:is_full_sync false}
         (select-keys (create-db-via-api! {:is_full_sync false}) [:is_full_sync]))))


;; ## DELETE /api/database/:id
;; Check that we can delete a Database
(deftest delete-a-db
  (tt/with-temp Database [db]
    ((test-users/user->client :crowberto) :delete 204 (format "database/%d" (:id db)))
    (is (false? (db/exists? Database :id (u/get-id db))))))

;; ## PUT /api/database/:id
;; Check that we can update fields in a Database
(deftest can-update-db-fields
  (tt/with-temp Database [{db-id :id}]
    (let [updates {:name         "Cam's Awesome Toucan Database"
                   :engine       "h2"
                   :is_full_sync false
                   :details      {:host "localhost", :port 5432, :dbname "fakedb", :user "rastacan"}}]
      ((test-users/user->client :crowberto) :put 200 (format "database/%d" db-id) updates))
    (is (= {:details      {:host "localhost", :port 5432, :dbname "fakedb", :user "rastacan"}
            :engine       :h2
            :name         "Cam's Awesome Toucan Database"
            :is_full_sync false
            :features     (driver.u/features :h2)}
           (into {} (db/select-one [Database :name :engine :details :is_full_sync], :id db-id))))))

;; should be able to set `auto_run_queries` when updating a Database
(deftest set-auto-run-queries
  (tt/with-temp Database [{db-id :id}]
    (let [updates {:auto_run_queries false}]
      ((test-users/user->client :crowberto) :put 200 (format "database/%d" db-id) updates))
    (is (= {:auto_run_queries false}
           (into {} (db/select-one [Database :auto_run_queries], :id db-id))))))


(def ^:private default-table-details
  {:description             nil
   :entity_name             nil
   :entity_type             "entity/GenericTable"
   :caveats                 nil
   :points_of_interest      nil
   :visibility_type         nil
   :active                  true
   :show_in_getting_started false})

(defn- table-details [table]
  (-> default-table-details
      (merge
       (select-keys table [:active :created_at :db_id :description :display_name :entity_name :entity_type :fields_hash
                           :id :name :rows :schema :updated_at :visibility_type]))
      (update :entity_type (fn [entity-type]
                             (when entity-type
                               (str "entity/" (name entity-type)))))))

;; ## `GET /api/database`
;; Test that we can get all the DBs (ordered by name, then driver)

(defn- sorted-databases [dbs]
  (vec
   (sort-by
    (fn [{db-name :name, driver :engine}]
      [(str/lower-case db-name) (str/lower-case (u/qualified-name driver))])
    dbs)))

(defn- test-driver-database [db-id]
  (merge
   default-db-details
   (db/select-one [Database :created_at :updated_at :name :timezone] :id db-id)
   {:engine             (u/qualified-name ::test-driver)
    :id                 db-id
    :native_permissions "write"
    :features           (map u/qualified-name (driver.u/features ::test-driver))}))

(defn- all-test-data-databases []
  (for [driver (conj @tx.env/test-drivers :h2)
        ;; GA has no test extensions impl and thus data/db doesn't work with it
        ;; Also it doesn't work for Druid either because DB must be flattened
        :when  (not (#{:googleanalytics :druid} driver))]
    (merge
     default-db-details
     (select-keys (driver/with-driver driver (data/db)) [:created_at :id :updated_at :timezone])
     {:engine             (u/qualified-name driver)
      :name               "test-data"
      :native_permissions "write"
      :features           (map u/qualified-name (driver.u/features driver))})))

(defn- api-database-list [db-name user-kw & options]
  (filter
   #(contains? #{"test-data" db-name} (:name %))
   (apply (test-users/user->client user-kw) :get 200 "database" options)))

;; Database details *should not* come back for Rasta since she's not a superuser
(deftest no-dbs-for-rasta
  (tt/with-temp* [Database [{db-id :id, db-name :name} {:engine (u/qualified-name ::test-driver)}]]
    (is (= (sorted-databases
            (cons
             (test-driver-database db-id)
             (all-test-data-databases)))
           (api-database-list db-name :rasta)))))

;; GET /api/databases (include tables)
(deftest db-endpoint-includes-tables
  (tt/with-temp* [Database [{db-id :id, db-name :name} {:engine (u/qualified-name ::test-driver)}]]
    (is (= (sorted-databases
            (cons
             (assoc (test-driver-database db-id) :tables [])
             (for [db (all-test-data-databases)]
               (assoc db :tables (->> (db/select Table, :db_id (u/get-id db), :active true)
                                      (map table-details)
                                      (sort-by (comp str/lower-case :name)))))))
           (api-database-list db-name :rasta, :include_tables true)))))


;; ## GET /api/database/:id/metadata
(def ^:private default-field-details
  {:description        nil
   :caveats            nil
   :points_of_interest nil
   :active             true
   :position           0
   :target             nil
   :preview_display    true
   :parent_id          nil
   :settings           nil})

(defn- field-details [field]
  (merge
   default-field-details
   (select-keys
    field
    [:updated_at :id :created_at :last_analyzed :fingerprint :fingerprint_version :fk_target_field_id])))

(deftest TODO-give-this-a-name
  (is (= (merge default-db-details
                (select-keys (data/db) [:created_at :id :updated_at :timezone])
                {:engine   "h2"
                 :name     "test-data"
                 :features (map u/qualified-name (driver.u/features :h2))
                 :tables   [(merge
                             default-table-details
                             (db/select-one [Table :created_at :updated_at :fields_hash] :id (data/id :categories))
                             {:schema       "PUBLIC"
                              :name         "CATEGORIES"
                              :display_name "Categories"
                              :fields       [(merge
                                              (field-details (Field (data/id :categories :id)))
                                              {:table_id         (data/id :categories)
                                               :special_type     "type/PK"
                                               :name             "ID"
                                               :display_name     "ID"
                                               :database_type    "BIGINT"
                                               :base_type        "type/BigInteger"
                                               :visibility_type  "normal"
                                               :has_field_values "none"})
                                             (merge
                                              (field-details (Field (data/id :categories :name)))
                                              {:table_id         (data/id :categories)
                                               :special_type     "type/Name"
                                               :name             "NAME"
                                               :display_name     "Name"
                                               :database_type    "VARCHAR"
                                               :base_type        "type/Text"
                                               :visibility_type  "normal"
                                               :has_field_values "list"})]
                              :segments     []
                              :metrics      []
                              :rows         nil
                              :id           (data/id :categories)
                              :db_id        (data/id)})]})
         (let [resp ((test-users/user->client :rasta) :get 200 (format "database/%d/metadata" (data/id)))]
           (assoc resp :tables (filter #(= "CATEGORIES" (:name %)) (:tables resp)))))))


;;; GET /api/database/:id/autocomplete_suggestions

(defn- suggestions-with-prefix [prefix]
  ((test-users/user->client :rasta) :get 200 (format "database/%d/autocomplete_suggestions" (data/id)) :prefix prefix))

(deftest succestions-with-prefix
  (is (= [["USERS" "Table"]
          ["USER_ID" "CHECKINS :type/Integer :type/FK"]]
         (suggestions-with-prefix "u")))

  (is (= [["CATEGORIES" "Table"]
          ["CHECKINS" "Table"]
          ["CATEGORY_ID" "VENUES :type/Integer :type/FK"]]
         (suggestions-with-prefix "c")))

  (is (= [["CATEGORIES" "Table"]
          ["CATEGORY_ID" "VENUES :type/Integer :type/FK"]]
         (suggestions-with-prefix "cat"))))


;;; GET /api/database?include_cards=true
;; Check that we get back 'virtual' tables for Saved Questions
(defn- card-with-native-query {:style/indent 1} [card-name & {:as kvs}]
  (merge
   {:name          card-name
    :database_id   (data/id)
    :dataset_query {:database (data/id)
                    :type     :native
                    :native   {:query (format "SELECT * FROM VENUES")}}}
   kvs))

(defn- card-with-mbql-query {:style/indent 1} [card-name & {:as inner-query-clauses}]
  {:name          card-name
   :database_id   (data/id)
   :dataset_query {:database (data/id)
                   :type     :query
                   :query    inner-query-clauses}})

(defn- saved-questions-virtual-db {:style/indent 0} [& card-tables]
  {:name               "Saved Questions"
   :id                 mbql.s/saved-questions-virtual-database-id
   :features           ["basic-aggregations"]
   :tables             card-tables
   :is_saved_questions true})

(defn- virtual-table-for-card [card & {:as kvs}]
  (merge
   {:id           (format "card__%d" (u/get-id card))
    :db_id        (:database_id card)
    :display_name (:name card)
    :schema       "Everything else"
    :description  nil}
   kvs))

(deftest saved-questions-db-is-last-on-list
  (tt/with-temp* [Card [card (card-with-native-query "Kanye West Quote Views Per Month")]]
    ;; run the Card which will populate its result_metadata column
    ((test-users/user->client :crowberto) :post 202 (format "card/%d/query" (u/get-id card)))
    ;; Now fetch the database list. The 'Saved Questions' DB should be last on the list
    (is (= (-> card
               virtual-table-for-card
               saved-questions-virtual-db)
           (last ((test-users/user->client :crowberto) :get 200 "database" :include_cards true))))))

;; Make sure saved questions are NOT included if the setting is disabled
(deftest saved-questions-not-inluded-if-setting-disabled
  (is (nil? (tt/with-temp Card [card (card-with-native-query "Kanye West Quote Views Per Month")]
              (tu/with-temporary-setting-values [enable-nested-queries false]
                ;; run the Card which will populate its result_metadata column
                ((test-users/user->client :crowberto) :post 202 (format "card/%d/query" (u/get-id card)))
                ;; Now fetch the database list. The 'Saved Questions' DB should NOT be in the list
                (some (fn [database]
                        (when (= (u/get-id database) mbql.s/saved-questions-virtual-database-id)
                          database))
                      ((test-users/user->client :crowberto) :get 200 "database" :include_cards true)))))))


;; make sure that GET /api/database?include_cards=true groups pretends COLLECTIONS are SCHEMAS
(deftest pretend-collections-are-schemas
  (tt/with-temp* [Collection [stamp-collection {:name "Stamps"}]
                  Collection [coin-collection  {:name "Coins"}]
                  Card       [stamp-card (card-with-native-query "Total Stamp Count", :collection_id (u/get-id stamp-collection))]
                  Card       [coin-card  (card-with-native-query "Total Coin Count",  :collection_id (u/get-id coin-collection))]]
    ;; run the Cards which will populate their result_metadata columns
    (doseq [card [stamp-card coin-card]]
      ((test-users/user->client :crowberto) :post 202 (format "card/%d/query" (u/get-id card))))
    ;; Now fetch the database list. The 'Saved Questions' DB should be last on the list. Cards should have their
    ;; Collection name as their Schema
    (is (= (saved-questions-virtual-db
            (virtual-table-for-card coin-card  :schema "Coins")
            (virtual-table-for-card stamp-card :schema "Stamps"))
           (last ((test-users/user->client :crowberto) :get 200 "database" :include_cards true))))))

(defn- fetch-virtual-database []
  (some #(when (= (:name %) "Saved Questions")
           %)
        ((test-users/user->client :crowberto) :get 200 "database" :include_cards true)))

;; make sure that GET /api/database?include_cards=true removes Cards that have ambiguous columns
(deftest remove-cards-with-ambiguous-columns
  (tt/with-temp* [Card [ok-card         (assoc (card-with-native-query "OK Card")         :result_metadata [{:name "cam"}])]
                  Card [cambiguous-card (assoc (card-with-native-query "Cambiguous Card") :result_metadata [{:name "cam"} {:name "cam_2"}])]]
    (is (= (-> ok-card
               virtual-table-for-card
               saved-questions-virtual-db)
           (fetch-virtual-database)))))

;; make sure that GET /api/database/include_cards=true removes Cards that belong to a driver that doesn't support
;; nested queries
(driver/register! ::no-nested-query-support
                  :parent :sql-jdbc
                  :abstract? true)

(defmethod driver/supports? [::no-nested-query-support :nested-queries] [_ _] false)

(deftest TODO-name-test-1
  (tt/with-temp* [Database [bad-db   {:engine ::no-nested-query-support, :details {}}]
                  Card     [bad-card {:name            "Bad Card"
                                      :dataset_query   {:database (u/get-id bad-db)
                                                        :type     :native
                                                        :native   {:query "[QUERY GOES HERE]"}}
                                      :result_metadata [{:name "sparrows"}]
                                      :database_id     (u/get-id bad-db)}]
                  Card     [ok-card  (assoc (card-with-native-query "OK Card")
                                            :result_metadata [{:name "finches"}])]]
    (is (= (-> ok-card
               virtual-table-for-card
               saved-questions-virtual-db)
           (fetch-virtual-database)))))


;; make sure that GET /api/database?include_cards=true removes Cards that use cumulative-sum and cumulative-count
;; aggregations
(defn- ok-mbql-card []
  (assoc (card-with-mbql-query "OK Card"
                               :source-table (data/id :checkins))
         :result_metadata [{:name "num_toucans"}]))

;; cumulative count
(deftest cumulative-count
  (tt/with-temp* [Card [ok-card (ok-mbql-card)]
                  Card [_ (merge
                           (data/$ids checkins
                                      (card-with-mbql-query "Cum Count Card"
                                                            :source-table $$checkins
                                                            :aggregation  [[:cum-count]]
                                                            :breakout     [!month.date]))
                           {:result_metadata [{:name "num_toucans"}]})]]
    (is (= (-> ok-card
               virtual-table-for-card
               saved-questions-virtual-db)
           (fetch-virtual-database)))))

;; make sure that GET /api/database/:id/metadata works for the Saved Questions 'virtual' database
(deftest works-with-saved-questions-virtual-db
  (tt/with-temp* [Card [card (assoc (card-with-native-query "Birthday Card")
                                    :result_metadata [{:name "age_in_bird_years"}])]]
    (is (= (saved-questions-virtual-db
            (assoc (virtual-table-for-card card)
                   :fields [{:name                     "age_in_bird_years"
                             :table_id                 (str "card__" (u/get-id card))
                             :id                       ["field-literal" "age_in_bird_years" "type/*"]
                             :special_type             nil
                             :base_type                nil
                             :default_dimension_option nil
                             :dimension_options        []}]))
           ((test-users/user->client :crowberto) :get 200
            (format "database/%d/metadata" mbql.s/saved-questions-virtual-database-id))))))

;; if no eligible Saved Questions exist the virtual DB metadata endpoint should just return `nil`
(deftest return-nil-when-no-eligible-saved-questions
  (is (nil? ((test-users/user->client :crowberto) :get 200
             (format "database/%d/metadata" mbql.s/saved-questions-virtual-database-id)))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                CRON SCHEDULES!                                                 |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:private schedule-map-for-last-friday-at-11pm
  {:schedule_day   "fri"
   :schedule_frame "last"
   :schedule_hour  23
   :schedule_type  "monthly"})

(def ^:private schedule-map-for-hourly
  {:schedule_day   nil
   :schedule_frame nil
   :schedule_hour  nil
   :schedule_type  "hourly"})

;; Can we create a NEW database and give it custom schedules?
(deftest create-new-db-with-custom-schedules
  (is (= {:cache_field_values_schedule "0 0 23 ? * 6L *"
          :metadata_sync_schedule      "0 0 * * * ? *"}
         (let [db-name (tu/random-name)]
           (try (let [db (with-redefs [driver/available? (constantly true)]
                           ((test-users/user->client :crowberto) :post 200 "database"
                            {:name      db-name
                             :engine    (u/qualified-name ::test-driver)
                             :details   {:db "my_db"}
                             :schedules {:cache_field_values schedule-map-for-last-friday-at-11pm
                                         :metadata_sync      schedule-map-for-hourly}}))]
                  (into {} (db/select-one [Database :cache_field_values_schedule :metadata_sync_schedule] :id (u/get-id db))))
                (finally (db/delete! Database :name db-name)))))))

;; Can we UPDATE the schedules for an existing database?
(deftest update-schedules-for-existing-db
  (is (= {:cache_field_values_schedule "0 0 23 ? * 6L *"
          :metadata_sync_schedule      "0 0 * * * ? *"}
         (tt/with-temp Database [db {:engine "h2", :details (:details (data/db))}]
           ((test-users/user->client :crowberto) :put 200 (format "database/%d" (u/get-id db))
            (assoc db
                   :schedules {:cache_field_values schedule-map-for-last-friday-at-11pm
                               :metadata_sync      schedule-map-for-hourly}))
           (into {} (db/select-one [Database :cache_field_values_schedule :metadata_sync_schedule] :id (u/get-id db)))))))

;; If we FETCH a database will it have the correct 'expanded' schedules?
(deftest fetch-db-with-expanded-schedules
  (is (= {:cache_field_values_schedule "0 0 23 ? * 6L *"
          :metadata_sync_schedule      "0 0 * * * ? *"
          :schedules                   {:cache_field_values schedule-map-for-last-friday-at-11pm
                                        :metadata_sync      schedule-map-for-hourly}}
         (tt/with-temp Database [db {:metadata_sync_schedule      "0 0 * * * ? *"
                                     :cache_field_values_schedule "0 0 23 ? * 6L *"}]
           (-> ((test-users/user->client :crowberto) :get 200 (format "database/%d" (u/get-id db)))
               (select-keys [:cache_field_values_schedule :metadata_sync_schedule :schedules]))))))

;; Five minutes
(def ^:private long-timeout (* 5 60 1000))

(defn- deliver-when-db [promise-to-deliver expected-db]
  (fn [db]
    (when (= (u/get-id db) (u/get-id expected-db))
      (deliver promise-to-deliver true))))

;; Can we trigger a metadata sync for a DB?
(deftest trigger-metadata-sync-for-db
  (is (= [true true]
         (let [sync-called?    (promise)
               analyze-called? (promise)]
           (tt/with-temp Database [db {:engine "h2", :details (:details (data/db))}]
             (with-redefs [sync-metadata/sync-db-metadata! (deliver-when-db sync-called? db)
                           analyze/analyze-db!             (deliver-when-db analyze-called? db)]
               ((test-users/user->client :crowberto) :post 200 (format "database/%d/sync_schema" (u/get-id db)))
               ;; Block waiting for the promises from sync and analyze to be delivered. Should be delivered instantly,
               ;; however if something went wrong, don't hang forever, eventually timeout and fail
               [(deref sync-called? long-timeout :sync-never-called)
                (deref analyze-called? long-timeout :analyze-never-called)]))))))

;; (Non-admins should not be allowed to trigger sync)
(deftest non-admins-cant-trigger-sync
  (is (= "You don't have permissions to do that."
         ((test-users/user->client :rasta) :post 403 (format "database/%d/sync_schema" (data/id))))))

;; Can we RESCAN all the FieldValues for a DB?
(deftest can-rescan-fieldvalues-for-a-db
  (is (= :sync-called
         (let [update-field-values-called? (promise)]
           (tt/with-temp Database [db {:engine "h2", :details (:details (data/db))}]
             (with-redefs [field-values/update-field-values! (fn [synced-db]
                                                               (when (= (u/get-id synced-db) (u/get-id db))
                                                                 (deliver update-field-values-called? :sync-called)))]
               ((test-users/user->client :crowberto) :post 200 (format "database/%d/rescan_values" (u/get-id db)))
               (deref update-field-values-called? long-timeout :sync-never-called)))))))

;; (Non-admins should not be allowed to trigger re-scan)
(deftest nonadmins-cant-trigger-rescan
  (is (= "You don't have permissions to do that."
         ((test-users/user->client :rasta) :post 403 (format "database/%d/rescan_values" (data/id))))))

;; Can we DISCARD all the FieldValues for a DB?
(deftest discard-db-fieldvalues
  (is (= {:values-1-still-exists? false
          :values-2-still-exists? false}
         (tt/with-temp* [Database    [db       {:engine "h2", :details (:details (data/db))}]
                         Table       [table-1  {:db_id (u/get-id db)}]
                         Table       [table-2  {:db_id (u/get-id db)}]
                         Field       [field-1  {:table_id (u/get-id table-1)}]
                         Field       [field-2  {:table_id (u/get-id table-2)}]
                         FieldValues [values-1 {:field_id (u/get-id field-1), :values [1 2 3 4]}]
                         FieldValues [values-2 {:field_id (u/get-id field-2), :values [1 2 3 4]}]]
           ((test-users/user->client :crowberto) :post 200 (format "database/%d/discard_values" (u/get-id db)))
           {:values-1-still-exists? (db/exists? FieldValues :id (u/get-id values-1))
            :values-2-still-exists? (db/exists? FieldValues :id (u/get-id values-2))}))))

;; (Non-admins should not be allowed to discard all FieldValues)
(deftest nonadmins-cant-discard-all-fieldvalues
  (is (= "You don't have permissions to do that."
         ((test-users/user->client :rasta) :post 403 (format "database/%d/discard_values" (data/id))))))


;;; Tests for /POST /api/database/validate

;; For some stupid reason the *real* version of `test-database-connection` is set up to do nothing for tests. I'm
;; guessing it's done that way so we can save invalid DBs for some silly tests. Instead of doing it the right way
;; and using `with-redefs` to disable it in the few tests where it makes sense, we actually have to use `with-redefs`
;; here to simulate its *normal* behavior. :unamused:
(defn- test-database-connection [engine details]
  (if (driver.u/can-connect-with-details? (keyword engine) details)
    nil
    {:valid false, :message "Error!"}))

(deftest nonadmins-cant-do-something
  (is (= "You don't have permissions to do that."
         (with-redefs [database-api/test-database-connection test-database-connection]
           ((test-users/user->client :rasta) :post 403 "database/validate"
            {:details {:engine :h2, :details (:details (data/db))}})))))

(deftest gets-details
  (is (= (:details (data/db))
         (with-redefs [database-api/test-database-connection test-database-connection]
           (#'database-api/test-connection-details "h2" (:details (data/db)))))))

(deftest TODO-is-valid
  (is (= {:valid true}
         (with-redefs [database-api/test-database-connection test-database-connection]
           ((test-users/user->client :crowberto) :post 200 "database/validate"
            {:details {:engine :h2, :details (:details (data/db))}})))))

(deftest TODO-rename-is-not-valid
  (is (= {:valid false, :message "Error!"}
         (with-redefs [database-api/test-database-connection test-database-connection]
           (tu.log/suppress-output
            (#'database-api/test-connection-details "h2" {:db "ABC"}))))))

(deftest TODO-rename-is-also-nt-valid
  (is (= {:valid false}
         (with-redefs [database-api/test-database-connection test-database-connection]
           (tu.log/suppress-output
            ((test-users/user->client :crowberto) :post 200 "database/validate"
             {:details {:engine :h2, :details {:db "ABC"}}}))))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                      GET /api/database/:id/schemas & GET /api/database/:id/schema/:schema                      |
;;; +----------------------------------------------------------------------------------------------------------------+

;; Tests for GET /api/database/:id/schemas: should work if user has full DB perms...
(deftest get-schemas-if-user-has-full-db-perms
  (is (= ["schema1"]
         (tt/with-temp* [Database [{db-id :id}]
                         Table    [_ {:db_id db-id, :schema "schema1"}]
                         Table    [_ {:db_id db-id, :schema "schema1"}]]
           ((test-users/user->client :rasta) :get 200 (format "database/%d/schemas" db-id))))))

;; ...or full schema perms...
(deftest get-schema-if-user-has-full-schema-perms
  (is (= ["schema1"]
         (tt/with-temp* [Database [{db-id :id}]
                         Table    [_ {:db_id db-id, :schema "schema1"}]
                         Table    [_ {:db_id db-id, :schema "schema1"}]]
           (perms/revoke-permissions! (perms-group/all-users) db-id)
           (perms/grant-permissions!  (perms-group/all-users) db-id "schema1")
           ((test-users/user->client :rasta) :get 200 (format "database/%d/schemas" db-id))))))

;; ...or just table read perms...
(deftest get-schema-if-user-has-table-read-perms
  (is (= ["schema1"]
         (tt/with-temp* [Database [{db-id :id}]
                         Table    [t1          {:db_id db-id, :schema "schema1"}]
                         Table    [t2          {:db_id db-id, :schema "schema1"}]]
           (perms/revoke-permissions! (perms-group/all-users) db-id)
           (perms/grant-permissions!  (perms-group/all-users) db-id "schema1" t1)
           (perms/grant-permissions!  (perms-group/all-users) db-id "schema1" t2)
           ((test-users/user->client :rasta) :get 200 (format "database/%d/schemas" db-id))))))

;; Multiple schemas are ordered by name
(deftest multiple-schemas-are-ordered-by-name
  (is (= ["schema1" "schema2" "schema3"]
         (tt/with-temp* [Database [{db-id :id}]
                         Table    [_ {:db_id db-id, :schema "schema3"}]
                         Table    [_ {:db_id db-id, :schema "schema2"}]
                         Table    [_ {:db_id db-id, :schema "schema1"}]]
           ((test-users/user->client :rasta) :get 200 (format "database/%d/schemas" db-id))))))

;; Can we fetch the Tables in a Schema? (If we have full DB perms)
(deftest can-fetch-tables-in-a-schmea-with-full-db-perms
  (is (= ["t1" "t3"]
         (tt/with-temp* [Database [{db-id :id}]
                         Table    [_ {:db_id db-id, :schema "schema1", :name "t1"}]
                         Table    [_ {:db_id db-id, :schema "schema2"}]
                         Table    [_ {:db_id db-id, :schema "schema1", :name "t3"}]]
           (map :name ((test-users/user->client :rasta) :get 200 (format "database/%d/schema/%s" db-id "schema1")))))))

;; Can we fetch the Tables in a Schema? (If we have full schema perms)
(deftest can-fetch-tables-in-a-schmea-with-full-schema-perms
  (is (= ["t1" "t3"]
         (tt/with-temp* [Database [{db-id :id}]
                         Table    [_ {:db_id db-id, :schema "schema1", :name "t1"}]
                         Table    [_ {:db_id db-id, :schema "schema2"}]
                         Table    [_ {:db_id db-id, :schema "schema1", :name "t3"}]]
           (perms/revoke-permissions! (perms-group/all-users) db-id)
           (perms/grant-permissions!  (perms-group/all-users) db-id "schema1")
           (map :name ((test-users/user->client :rasta) :get 200 (format "database/%d/schema/%s" db-id "schema1")))))))

;; Can we fetch the Tables in a Schema? (If we have full Table perms)
(deftest can-fetch-tables-in-a-schmea-with-full-table-perms
  (is (= ["t1" "t3"]
         (tt/with-temp* [Database [{db-id :id}]
                         Table    [t1 {:db_id db-id, :schema "schema1", :name "t1"}]
                         Table    [_  {:db_id db-id, :schema "schema2"}]
                         Table    [t3 {:db_id db-id, :schema "schema1", :name "t3"}]]
           (perms/revoke-permissions! (perms-group/all-users) db-id)
           (perms/grant-permissions!  (perms-group/all-users) db-id "schema1" t1)
           (perms/grant-permissions!  (perms-group/all-users) db-id "schema1" t3)
           (map :name ((test-users/user->client :rasta) :get 200 (format "database/%d/schema/%s" db-id "schema1")))))))

;; GET /api/database/:id/schemas should return a 403 for a user that doesn't have read permissions
(deftest return-403-when-you-aint-got-permission
  (is (= "You don't have permissions to do that."
         (tt/with-temp* [Database [{database-id :id}]
                         Table    [_ {:db_id database-id, :schema "test"}]]
           (perms/revoke-permissions! (perms-group/all-users) database-id)
           ((test-users/user->client :rasta) :get 403 (format "database/%s/schemas" database-id))))))

;; GET /api/database/:id/schemas should exclude schemas for which the user has no perms
(deftest exclude-schemas-when-user-aint-got-perms-for-them
  (is (= ["schema-with-perms"]
         (tt/with-temp* [Database [{database-id :id}]
                         Table    [_ {:db_id database-id, :schema "schema-with-perms"}]
                         Table    [_ {:db_id database-id, :schema "schema-without-perms"}]]
           (perms/revoke-permissions! (perms-group/all-users) database-id)
           (perms/grant-permissions!  (perms-group/all-users) database-id "schema-with-perms")
           ((test-users/user->client :rasta) :get 200 (format "database/%s/schemas" database-id))))))

;; GET /api/database/:id/schema/:schema should return a 403 for a user that doesn't have read permissions FOR THE DB...
(deftest return-403-when-user-doesnt-have-db-permissions
  (is (= "You don't have permissions to do that."
         (tt/with-temp* [Database [{database-id :id}]
                         Table    [{table-id :id} {:db_id database-id, :schema "test"}]]
           (perms/revoke-permissions! (perms-group/all-users) database-id)
           ((test-users/user->client :rasta) :get 403 (format "database/%s/schema/%s" database-id "test"))))))

;; ... or for the SCHEMA
(deftest return-403-when-user-doesnt-have-schema-permissions
  (is (= "You don't have permissions to do that."
         (tt/with-temp* [Database [{database-id :id}]
                         Table    [_ {:db_id database-id, :schema "schema-with-perms"}]
                         Table    [_ {:db_id database-id, :schema "schema-without-perms"}]]
           (perms/revoke-permissions! (perms-group/all-users) database-id)
           (perms/grant-permissions!  (perms-group/all-users) database-id "schema-with-perms")
           ((test-users/user->client :rasta) :get 403 (format "database/%s/schema/%s" database-id "schema-without-perms"))))))

;; Looking for a database that doesn't exist should return a 404
(deftest return-404-when-no-db
  (is (= "Not found."
         ((test-users/user->client :crowberto) :get 404 (format "database/%s/schemas" Integer/MAX_VALUE)))))

;; Check that a 404 returns if the schema isn't found
(deftest return-404-when-no-schema
  (is (= "Not found."
         (tt/with-temp* [Database [{db-id :id}]
                         Table    [{t1-id :id} {:db_id db-id, :schema "schema1"}]]
           ((test-users/user->client :crowberto) :get 404 (format "database/%d/schema/%s" db-id "not schema1"))))))


;; GET /api/database/:id/schema/:schema should exclude Tables for which the user has no perms
(deftest db-schema-endpoint-excludes-tables-when-user-has-no-perms
  (is (= ["table-with-perms"]
         (tt/with-temp* [Database [{database-id :id}]
                         Table    [table-with-perms {:db_id database-id, :schema "public", :name "table-with-perms"}]
                         Table    [_                {:db_id database-id, :schema "public", :name "table-without-perms"}]]
           (perms/revoke-permissions! (perms-group/all-users) database-id)
           (perms/grant-permissions!  (perms-group/all-users) database-id "public" table-with-perms)
           (map :name ((test-users/user->client :rasta) :get 200 (format "database/%s/schema/%s" database-id "public")))))))

;; GET /api/database/:id/schema/:schema should exclude inactive Tables
(deftest exclude-inactive-tables
  (is (= ["table"]
         (tt/with-temp* [Database [{database-id :id}]
                         Table    [_ {:db_id database-id, :schema "public", :name "table"}]
                         Table    [_ {:db_id database-id, :schema "public", :name "inactive-table", :active false}]]
           (map :name ((test-users/user->client :rasta) :get 200 (format "database/%s/schema/%s" database-id "public")))))))
