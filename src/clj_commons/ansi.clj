(ns clj-commons.ansi
  "Help with generating textual output that includes ANSI escape codes for formatting.
  The [[compose]] function is the best starting point.

  Reference: [ANSI Escape Codes @ Wikipedia](https://en.wikipedia.org/wiki/ANSI_escape_code#SGR)."
  (:require [clojure.string :as str]
            [clj-commons.pretty-impl :refer [csi padding]])
  (:import (clojure.lang Namespace)))

(defn- is-ns-loaded?
  [sym]
  (some? (Namespace/find sym)))

(defn- to-boolean
  [s]
  (-> s str/trim str/lower-case (= "true")))

(def ^:dynamic *color-enabled*
  "Determines if ANSI colors are enabled; color is a deliberate misnomer, as we lump
  other font characteristics (bold, underline, italic, etc.) along with colors.

  This will be false if the environment variable NO_COLOR is non-blank.

  Otherwise, the JVM system property `clj-commons.ansi.enabled` (if present) determines
  the value; \"true\" enables colors, any other value disables colors.

  If the property is null, then the default is a best guess based on the environment:
  if either the `nrepl.core` namespace is present, or the JVM has a console  (via `(System/console)`),
  then color will be enabled.

  The nrepl.core check has been verified to work with Cursive, with `lein repl`, and with `clojure` (or `clj`)."
  (if (seq (System/getenv "NO_COLOR"))
    false
    (let [flag (System/getProperty "clj-commons.ansi.enabled")]
      (cond
        (some? flag) (to-boolean flag)

        (is-ns-loaded? 'nrepl.core)
        true

        :else
        (some? (System/console))))))

(defmacro when-color-enabled
  "Evaluates its body only when [[*color-enabled*]] is true."
  [& body]
  `(when *color-enabled* ~@body))

;; select graphic rendition
(def ^:const ^:private sgr
  "The Select Graphic Rendition suffix: m"
  "m")

(def ^:const ^:private reset-font
  "ANSI escape code to resets all font characteristics."
  (str csi sgr))

(def ^:private font-terms
  (reduce merge
          {:bold [:bold "1"]
           :plain [:bold "22"]
           :faint [:bold "2"]

           :italic [:italic "3"]
           :roman [:italic "23"]

           :inverse [:inverse "7"]
           :normal [:inverse "27"]

           :underlined [:underlined "4"]
           :not-underlined [:underlined "24"]}
          (map-indexed
            (fn [index color-name]
              {(keyword color-name) [:foreground (str (+ 30 index))]
               (keyword (str "bright-" color-name)) [:foreground (str (+ 90 index))]
               (keyword (str color-name "-bg")) [:background (str (+ 40 index))]
               (keyword (str "bright-" color-name "-bg")) [:background (str (+ 100 index))]})
            ["black" "red" "green" "yellow" "blue" "magenta" "cyan" "white"])))

(defn- delta
  [active current k]
  (let [current-value (get current k)]
    (when (not= (get active k) current-value)
      current-value)))

(defn- compose-font
  ^String [active current]
  (when-color-enabled
    (let [codes (keep #(delta active current %) [:foreground :background :bold :italic :inverse :underlined])]
      (when (seq codes)
        (str csi (str/join ";" codes) sgr)))))

(defn- split-font-def*
  [font-def]
  (assert (simple-keyword? font-def) "expected a simple keyword to define the font characteristics")
  (mapv keyword (str/split (name font-def) #"\.")))

(def ^:private split-font-def (memoize split-font-def*))

(defn- update-font-data-from-font-def
  [font-data font-def]
  (if (some? font-def)
    (let [ks (split-font-def font-def)
          f (fn [font-data term]
              (let [[font-k font-value] (or (get font-terms term)
                                            (throw (ex-info (str "unexpected font term: " term)
                                                            {:font-term term
                                                             :font-def font-def
                                                             :available-terms (->> font-terms keys sort vec)})))]
                (assoc! font-data font-k font-value)))]
      (persistent! (reduce f (transient font-data) ks)))
    font-data))

(defn- extract-span-decl
  [value]
  (cond
    (nil? value)
    nil

    (keyword? value)
    {:font value}

    (map? value)
    value

    :else
    (throw (ex-info "invalid span declaration"
                    {:font-decl value}))))

(defn- blank? [value]
  (or (nil? value)
      (= "" value)))

(defn- normalize-markup
  "Normalizes markup to span vectors, while keeping track of the total length of string values."
  [coll *length]
  (let [f (fn reducer [result input]
            (cond
              (blank? input)
              result

              (vector? input)
              (let [decl (extract-span-decl (first input))
                    ;; TODO: Maybe we can actually allow nested width-padded spans?
                    _ (when (:width decl)
                        (throw (ex-info "can only track one span width at a time"
                                        {:input input})))
                    ;; next on vector is not a vector itself, fortunately
                    span (reduce reducer [decl] (next input))]
                (conj result span))

              (sequential? input)
              ;; Convert to a span with a nil decl
              (let [sub-span (reduce reducer [nil] input)]
                (conj result sub-span))

              :else
              (let [value-str ^String (.toString input)]
                (vswap! *length + (.length value-str))
                (conj result value-str))))]
    (reduce f [] coll)))

(defn- collect-markup
  [state input]
  (cond
    (blank? input)
    state

    (vector? input)
    (let [[first-element & inputs] input
          {:keys [font width pad] :as span-decl} (extract-span-decl first-element)]
      (if width
        (let [;; Transform this span and everything below it into easily managed span vectors, starting
              ;; with a version of this span decl.
              span-decl' (dissoc span-decl :width :pad)
              *length (volatile! 0)
              inputs' (into [span-decl'] (normalize-markup inputs *length))
              spaces (padding (- width @*length))
              ;; Add the padding in the desired position; this ensures that the logic that generates
              ;; ANSI escape codes occurs correctly, with the added spaces getting the font for this span.
              padded (if (= :right pad)
                       (conj inputs' spaces)
                       ;; An "insert-at" for vectors would be nice
                       (into [(first inputs') spaces] (next inputs')))]
          (recur state padded))
        ;; Normal (no width tracking)
        (let [{:keys [current]} state]
          (-> (reduce collect-markup
                      (-> state
                          (update :current update-font-data-from-font-def font)
                          (update :stack conj current))
                      inputs)
              (assoc :current current
                     :tracking-width? false)
              (update :stack pop)))))

    ;; Lists, lazy-lists, etc: processed recursively
    (sequential? input)
    (reduce collect-markup state input)

    :else
    (let [{:keys [active current ^StringBuilder buffer]} state
          state' (if (= active current)
                   state
                   (let [font-str (compose-font active current)]
                     (when font-str
                       (.append buffer font-str))
                     (cond-> (assoc state :active current)
                             ;; Signal that a reset is needed at the very end
                             font-str (assoc :dirty? true))))]
      (.append buffer ^String (.toString input))
      state')))

(defn compose
  "Given a Hiccup-inspired data structure, composes and returns a string that includes ANSI formatting codes
  for font color and other characteristics.

  The data structure may consist of literal values (strings, numbers, etc.) that are formatted
  with `str` and concatenated.

  Nested sequences are composed recursively; this (for example) allows the output from
  `map` or `for` to be mixed into the composed string seamlessly.

  Nested vectors represent _spans_, a sequence of values with a specific visual representation.
  The first element in a span vector declares the visual properties of the span: the color (including
  other characteristics such as bold or underline), and the width and padding (described later).
  Spans may be nested.

  The declaration is usually a keyword, to define just the font.
  The font def contains one or more terms, separated by periods.

  The terms:

  - foreground color:  e.g. `red` or `bright-red`
  - background color: e.g., `green-bg` or `bright-green-bg`
  - boldness: `bold`, `faint`, or `plain`
  - italics: `italic` or `roman`
  - inverse: `inverse` or `normal`
  - underline: `underlined` or `not-underlined`

  e.g.

  ```
  (compose [:yellow \"Warning: the \" [:bold.bright-white.bright-red-bg \"reactor\"]
    \" is about to \"
    [:italic.bold.red \"meltdown!\"]])
  => ...
  ```

  The order of the terms does not matter. Behavior for conflicting terms (e.g., `:blue.green.black`)
  is not defined.


  Font defs apply on top of the font def of the enclosing span, and the outer span's font def
  is restored at the end of the inner span, e.g. `[:red \" RED \" [:bold \"RED/BOLD\"] \" RED \"]`.

  A font def may also be nil, to indicate no change in font.

  `compose` presumes that on entry the current font is plain (default foreground and background, not bold,
   or inverse, or italic, or underlined) and appends a reset sequence to the end of the returned string to
   ensure that later output is also plain.

  The core colors are `black`, `red`, `green`, `yellow`, `blue`, `magenta`, `cyan`, and `white`.

  When [[*color-enabled*]] is false, then any font defs are validated, but otherwise ignored (no ANSI codes
  will be included in the composed string).

  The span's font declaration may also be a map with the following keys:

  :font keyword
  : the font declaration

  :width number
  : the visual width of the span

  :pad keyword
  : where to pad the span, :left or :right; default is :left

  The map form of the font declaration is typically only used when a span width is specified.
  The span will be padded with spaces to ensure that it is the specified width.  `compose` tracks the number
  of characters inside the span, excluding any ANSI code sequences injected by `compose`.

  `compose` doesn't consider the characters; if the strings contain tabs, newlines, or ANSI code sequences
  not generated by `compose`, the calculation of the span width will be incorrect.

  Only one span at a time can be tracked for width; if a nested span also specifies a width, `compose` will
  throw an exception.

  Example:

      [{:font :red
        :width 20} message]

  This will output the value of `message` in red text, padded with spaces on the left to be 20 characters.

  compose does not truncate a span to a width, it only pads if the span in too short."
  {:added "1.4.0"}
  [& inputs]
  (let [initial-font {:foreground "39"
                      :background "49"
                      :bold "22"
                      :italic "23"
                      :inverse "27"
                      :underlined "24"}
        buffer (StringBuilder. 100)
        {:keys [dirty?]} (collect-markup {:stack []
                                          :active initial-font
                                          :current initial-font
                                          :buffer buffer}
                                         inputs)]
    (when dirty?
      (.append buffer reset-font))
    (.toString buffer)))

