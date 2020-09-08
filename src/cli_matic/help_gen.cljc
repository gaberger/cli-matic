(ns cli-matic.help-gen
  "
  ## Generate help texts.

  "
  (:require [clojure.tools.cli :as cli]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [cli-matic.specs :as S]
            [cli-matic.utils :as U]
            [cli-matic.utils-v2 :as U2]

            [cli-matic.utils-candidates :as UB]
            [cli-matic.optionals :as OPT]))

(defn generate-section
  "Generates a section (as a collection of strings,
  possibly nested, but we'll flatten it out).
  If a section has no content, we return [].
  "
  [title lines]
  (if (empty? lines)
    []

    [(str title ":")
     (U/indent lines)
     ""]))

(defn generate-sections
  "Generates all sections.
  All those positional parameters are not that nice.
  "
  [name version usage commands opts-title opts examples]

  (vec
    (flatten
      [(generate-section "NAME" name)
       (generate-section "USAGE" usage)
       (generate-section "EXAMPLES" examples)
       (generate-section "VERSION" version)
       (generate-section "COMMANDS" commands)
       (generate-section opts-title opts)])))

(defn- expand-multiline-parts
  "Expands multilines within parts so that they can be
   aligned appropriately."
  [parts]
  (mapcat (fn [line-part]
            (let [p1 (map str/split-lines line-part)
                  max-col-length (apply max (map count p1))]
              (->> p1
                   (map #(concat % (repeat (- max-col-length (count %)) "")))
                   (apply mapv vector))))
          parts))

(defn- summarize-for-tools-cli
  "This customized `summarize` adapts the version from `clojure.tools.cli`
  to expand multiline parts so that they are aligned properly.

  Original description from clojure.tools.cli:

  Reduce options specs into a options summary for printing at a terminal.
  Note that the specs argument should be the compiled version. That effectively
  means that you shouldn't call summarize directly. When you call parse-opts
  you get back a :summary key which is the result of calling summarize (or
  your user-supplied :summary-fn option) on the compiled option specs."
  [specs]
  (if (seq specs)
    (let [show-defaults? (some #(and (:required %)
                                     (or (contains? % :default)
                                         (contains? % :default-fn))) specs)
          parts (-> (map (partial cli/make-summary-part show-defaults?) specs)
                    expand-multiline-parts)
          lens (apply map (fn [& cols] (apply max (map count cols))) parts)
          lines (cli/format-lines lens parts)]
      (str/join \newline lines))
    ""))

(defn get-options-summary
  "To get the summary of options, we pass options to
  tools.cli parse-opts and an empty set of arguments.
  Parsing will fail but we get the :summary.
  We then split it into a collection of lines.
  "
  [cfg subcmd]
  (let [cli-cfg (U2/rewrite-opts cfg subcmd)
        options-str (:summary
                      (cli/parse-opts [] cli-cfg
                                      :summary-fn summarize-for-tools-cli))]
    (str/split-lines options-str)))

(s/fdef
  get-options-summary
  :args (s/cat :cfg ::S/climatic-cfg
               :subcmd ::S/subcommand-path))

(defn get-first-rest-description-rows
  "get title and description of description rows"
  [row-or-rows]
  (cond
    (string? row-or-rows)
    [row-or-rows []]

    (zero? (count row-or-rows))
    ["?" []]

    :else
    [(first row-or-rows) (rest row-or-rows)]))

(defn generate-a-command
  "Maybe we should use a way to format commands

   E.g.
   (pp/cl-format true \"~{ ~vA  ~vA  ~vA ~}\" v)


   (clojure.pprint/cl-format true \"~3a ~a\" \"pippo\" \"pluto\")
   "

  [{:keys [command short description]}]

  (let [[des0 _] (get-first-rest-description-rows description)]
    ;(clojure.pprint/cl-format nil "~A~10,10T~A" command description)
    (str "  "
         (U/pad command short 20)
         " "
         des0)))

(defn generate-global-command-list
  "Creates a list of commands and descriptions.
   Commands are of kind ::S/commands
  "
  [commands]
  (let [padding (or (last (sort (mapv #(count (:command %)) commands))) 0)
        rows (mapv #(select-keys % [:command :description]) commands)
        flat-commands (vec (mapcat vals rows))
        format-string (str "~{~" (+ padding 5) "A~A~%~}")]
    (if (> (count flat-commands) 1)
      (clojure.string/split-lines (clojure.pprint/cl-format nil format-string flat-commands))
      (clojure.string/split-lines (clojure.pprint/cl-format nil "~{~A~%~}"  flat-commands))
      )))

(s/fdef
  generate-global-command-list
  :args (s/cat :commands ::S/subcommands)
  :ret (s/coll-of string?))

(defn generate-global-help
  "This is where we generate global help, so
  global attributes and subcommands.

  This is called for all branch nodes (not just the root),
  and not leaves, therefore we had to add the path as
  a parameter to tell who is who.

  "

  [cfg path]

  (let [cmd-cfg (U2/walk cfg path)
        name (U2/canonical-path-to-string
               (U2/as-canonical-path
                 cmd-cfg))
        version (U2/get-most-specific-value cfg path :version "-")
        descr (U2/get-most-specific-value cfg path :description [])
        [desc0 descr-extra] (get-first-rest-description-rows descr)
        this-cmd (last cmd-cfg)]
    (generate-sections
      [(str name " - " desc0) descr-extra]
      version
      (str name " [global-options] command [command options] [arguments...]")
      (generate-global-command-list (:subcommands this-cmd))
      "GLOBAL OPTIONS" (get-options-summary cfg path)
      (:examples this-cmd))))

(s/fdef
  generate-global-help
  :args (s/cat :cfg ::S/climatic-cfg
               :cmd ::S/subcommand-path)
  :ret (s/coll-of string?))

(defn arg-list-with-positional-entries
  "Creates the `[arguments...]`"
  [cfg cmd]
  (let [pos-args (sort-by :short (U2/list-positional-parms cfg cmd))]
    (if (empty? pos-args)
      "[arguments...]"
      (str/join " " (map :option pos-args)))))

(defn generate-subcmd-help
  "This is where we generate help for a specific subcommand."
  [cfg cmd]

  (let [cmd-cfg (U2/walk cfg cmd)
        path (U2/as-canonical-path cmd-cfg)
        path-but-last (reverse (rest (reverse path)))
        fullname (U2/canonical-path-to-string path)
        fullname-but-last (U2/canonical-path-to-string path-but-last)
        this-cmd (last cmd-cfg)

        name (:command this-cmd)
        shortname (:short this-cmd)
        name-short (if shortname
                     (str "[" name "|" shortname "]")
                     name)
        descr (:description this-cmd)
        version-or-nil (:version this-cmd)
        [desc0 descr-extra] (get-first-rest-description-rows descr)
        arglist (arg-list-with-positional-entries cfg cmd)]

    (generate-sections
      [(str fullname " - " desc0) descr-extra]
      version-or-nil
      (str fullname-but-last " " name-short " [command options] " arglist)
      nil
      "OPTIONS"
      (get-options-summary cfg cmd)
      (:examples this-cmd))))

(s/fdef
  generate-subcmd-help
  :args (s/cat :cfg ::S/climatic-cfg
               :cmd ::S/subcommand-path)
  :ret (s/coll-of string?))

(def MISTYPE-ERR-RATIO 0.35)

(defn generate-possible-mistypes
  "We go searching if we have any candidates
  to be considered mistypes.

  We require a miss ratio of [[MISTYPE-ERR-RATIO]]
  and we return them by similarity.

  "
  [wrong-subcmd commands aliases]
  (let [all-subcmds (-> []
                        (into commands)
                        (into aliases))]
    (UB/candidate-suggestions all-subcmds wrong-subcmd MISTYPE-ERR-RATIO)))

(s/fdef
  generate-possible-mistypes
  :args (s/cat :wrong-cmd string?
               :subcmd (s/coll-of (s/or :s string? :nil nil?))
               :aliases (s/coll-of (s/or :s string? :nil nil?)))
  :ret (s/coll-of string?))

(defn generate-help-possible-mistypes
  "If we have a wrong subcommand, can we guess what the correct
  one could have been?


  "
  [cfg wrong-subcmd]
  (let [appName (get-in cfg [:app :command] "?")
        commands (map :command (:commands cfg))
        aliases (map :short (:commnads cfg))

        candidates (generate-possible-mistypes wrong-subcmd commands aliases)

        error (str appName ": unknown sub-command '" wrong-subcmd "'.")]

    (if (empty? candidates)
      ; No candidates, just the error
      [error]

      ; Have some, let's show them.
      [error
       ""
       "The most similar subcommands are:"
       (mapv U/indent candidates)])))

(s/fdef
  generate-help-possible-mistypes
  :args (s/cat :cfg ::S/climatic-cfg-classic :cmd ::S/command)
  :ret (s/coll-of (s/or :str string?
                        :cs (s/coll-of string?))))

(defn getGlobalHelperFn
  [cfg path]
  (U2/get-most-specific-value cfg path :global-help generate-global-help))

(defn getSubcommandHelperFn
  [cfg path]
  (U2/get-most-specific-value cfg path :subcmd-help generate-subcmd-help))

(OPT/orchestra-instrument)
