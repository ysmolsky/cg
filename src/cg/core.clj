(ns cg.core
  (:use cg.common)
  (:use cg.ecs)
  (:use cg.comps)
  (:use cg.systems.move)
  (:use cg.systems.guide)
  (:use cg.systems.pathfind)
  (:use cg.systems.job-assign)
  (:use cg.systems.job-exec)
  (:import [com.badlogic.gdx.graphics Texture]
           [com.badlogic.gdx.graphics.glutils ShapeRenderer ShapeRenderer$ShapeType]
           [com.badlogic.gdx.graphics.g2d SpriteBatch BitmapFont])

  (:require [clojure.math.numeric-tower :as math]
            [cg.astar :as astar]
            [cg.map :as m]
            [cg.site :as s]
            [cg.units :as u]
            [quil.core :as q]
            [play-clj.core :as g]
            [play-clj.g2d :refer :all]
            [play-clj.utils :as gu])
  )

;; (set! *warn-on-reflection* true) 
;; (set! *unchecked-math* true)

(def ui
  {:window-width 1000
   :window-height 700
   :right-panel-width 300
   :window-border 10
   :tile-size 16
   :text-size 15
   :text-size-small 10
   :char-color 250
   :ui-color 40
   :wall-color 80
   :background-color 25
   :foreground-color 200
   :scroll-amount 10
   :fps-cap 60
   :ups-cap 60
   })

(declare pos2pix pix2pos epos2pix pos-middle tiles)

(def running (atom true))

(def scene {
            :beast [;;(health)
                    (position 15 15)
                    (renderable "b")]})

(defn new-player [w]
  (let [xy (s/random-place (:map w) s/passable? 40 40)
        ;;xy (first (s/rc-cells (:rc w) (first (s/rc-smallest-area (:rc w)))))
        ]
    (u/add-player w xy)))

(defn new-spawn [w]
  (-> (apply-times w 10 new-player)
      (m/init-visible (s/random-place (:map w) s/passable? 40 40))))

;;; State

;;; paused - if the game is paused
;;; mouse-actions - what does action of mouse have effect on. possible
;;; values: :move-to :dig :build-wall

(def map-size 100)

(def game {:world (atom nil)
           :viewport (atom [0 0])
           :paused (atom false)
           :mouse-action (atom :dig)
           :update-time (atom 0)
           :mouse-pos (atom [0 0])
           :tiles (atom {})})

(defn game! [key f & args]
  (apply swap! (key game) f args))

;;; view port

(defn tiles
  "how many tiles can fit in size"
  [size]
  (let [tile-size (ui :tile-size)]
    (quot (- size (* tile-size 2))
          tile-size)))

(defn vp-width []
  (tiles (- (q/width) (ui :right-panel-width))))
  
(defn vp-height []
  (tiles (q/height)))

(defn viewport []
  (let [[vp-x vp-y] @(game :viewport)]
    [vp-x
     vp-y
     (vp-width)
     (vp-height)]))

(defn in-viewport? [x y]
  (and (>= x 0)
       (>= y 0)
       (< x (vp-width))
       (< y (vp-height))))


;;; Convert stuff

(defn pos2pix
  "converts relative position (in tile) to position in pixels"
  [tile-pos]
  (+ (ui :tile-size)
     (* tile-pos (ui :tile-size))))

(defn epos2pix
  "converts entity position to pixels on the screen.
  shifts coord of entity to place entity in the middle of tile"
  [position]
  (pos2pix (+ 0.5 position)))

(defn pix2pos [pixel]
  (/ (float (- pixel (ui :tile-size)))
     (ui :tile-size)))

(defn pix->relative
  "converts position in pixels to position relative (viewport) in tiles"
  [xy]
  (map #(int (floor (pix2pos %))) xy))

(defn relative->absolute
  "converts relative (viewport) position to absolute (map) position"
  [[x y]]
  (let [[vp-x vp-y _ _] (viewport)]
    [(+ vp-x x)
     (+ vp-y y)]))

(def pix->absolute (comp relative->absolute pix->relative))

(defn dig-all [w]
  (loop [w w
         cells (s/range-2d 1 (dec (count (:map w))))]
    (if-let [fc (first cells)]
      (let [rc (rest cells)
            c (m/place w fc)
            [x y] fc]
        (if (and (s/visible? c)
                 (s/diggable? c))
          (recur (u/add-job w :dig x y "X") rc)
          (recur w rc)))
      w)))

;;; Events handlers

(defn on-start [w]
  (let [[site rc] (s/generate map-size s/new-cell)]
    (-> (new-ecs)
        (m/attach-to-ecs site rc)
        (new-spawn)
        (dig-all))))

(defn bound-viewport
  [[x y] [dx dy]]
  (let [w (vp-width)
        h (vp-height)]
    [(bound (- map-size w) (+ x dx))
     (bound (- map-size h) (+ y dy))]))

(def key-to-scroll {\w [0 -1]
                    \s [0 1]
                    \a [-1 0]
                    \d [1 0]})

(defn on-key
  "Handles key presses. Returns new state of the world"
  [w key]
  ;;(set-val w 0 :health :count key)
  (condp = key
    \p (do (q/exit) w)
    \space (swap! (game :paused) not)
    \f (reset! (game :mouse-action) :dig)
    \g (reset! (game :mouse-action) :move-to)
    \b (reset! (game :mouse-action) :build-wall)
    (let [delta (map #(* % (ui :scroll-amount))
                     (key-to-scroll key [0 0]))]
      (swap! (game :viewport) bound-viewport delta)
      ;;(prn delta @(game :viewport))
      ))
  w)

(defmulti on-mouse-designate (fn [_ action _ _] action))

(defmethod on-mouse-designate :dig [w action x y]
  (let [c (m/place w [x y])]
    (if (or (not (s/visible? c))
            (s/diggable? c))
      (u/add-job w :dig x y "X")
      w)))

(defmethod on-mouse-designate :build-wall [w action x y]
  (if (s/passable? (m/place w [x y]))
    (u/add-job w :build-wall x y "□")
    w))

(defn on-mouse
  [w x y e]
  ;; (prn x y e)
  (let [ids (get-cnames-ids w [:controllable])
        [x y] (pix->relative [x y])
        [abs-x abs-y] (relative->absolute [x y])
        action @(game :mouse-action)]
    (prn abs-x abs-y e action)
    (if (in-viewport? x y)
      (cond
       (= action :move-to) (update-entities w ids set-c (destination abs-x abs-y))
       (#{:dig :build-wall} action) (on-mouse-designate w action abs-x abs-y)
       :else w)
      w)))

(def systems [system-move
              system-guide
              system-path-find
              system-assign-jobs
              system-dig])

(defn on-tick
  "Handles ticks of the world, delta is the time passes since last tick"
  [w time]
  (let [[ts w] (reduce (fn [[ts w] s]
                         (let [t (timer)
                               w (s w time)
                               t (timer-end t)]
                           [(conj ts t) w]))
                       [[] w] systems)]
    (when (> (reduce + ts) 10)
      (prn ts))
    w))

;;; RENDERING STUFF

;; TODO: remove magic numbers
(defn text
  "x and y are tiles coordinates"
  [t x y]
  (q/text t
          (- (epos2pix x) 4)
          (+ (epos2pix y) 7)))

(defn image
  "x and y are tiles coordinates"
  [t x y]
  (q/image t
           (+ (epos2pix (dec x)) 8)
           (+ (epos2pix (dec y)) 8)
           (:tile-size ui) (:tile-size ui)))

(defn draw-ents [[vp-x vp-y w h] ents tiles]
  (doseq [e ents]
    (let [[x y] (coords (e :position))
          r (e :renderable)
          ch (:char r)
          x (- x vp-x)
          y (- y vp-y)]
      (if (and (< 0 (math/round x) w)
               (< 0 (math/round y) h))
        (if (keyword? ch)
          (image (ch tiles) x y)
          (text ch x y))))))

(defn draw-rect [x y color]
  (q/fill color)
  (q/rect (pos2pix x)
          (pos2pix y)
          (ui :tile-size)
          (ui :tile-size)))

(defn draw-tile [cell x y tiles]
  (when (s/visible? cell)
    (do
      (when-not (s/passable? cell)
        (image (:stone tiles) x y))
      (when-let [r (:region cell)]
        (image (:grass tiles) x y)
        #_(draw-rect x y 60)
        #_(text (str r) x y)))))

(defn draw-site [w f [vp-x vp-y width height] tiles]
  #_(q/fill 0)
  #_(q/rect (pos2pix 0)
          (pos2pix 0)
          (pos2pix (dec width))
          (pos2pix (dec height)))
  (let [m (:map w)]
    (doseq [x (range width)
            y (range height)]
      (let [cell (s/place m [(+ vp-x x) (+ vp-y y)])]
        (f cell x y tiles)))))

(defn draw-world [w viewport tiles]
  ;(q/text (str (get-cname-ids w :renderable)) 10 390)
  (q/fill (ui :wall-color))
  (q/text-font (q/state :font-monaco) (ui :text-size-small))
  (q/no-stroke)
  (draw-site w draw-tile viewport tiles)
  (q/fill (ui :char-color))
  (q/text-font (q/state :font-monaco) (ui :text-size))
  (draw-ents viewport (get-cnames-ents w (node :render)) tiles)
  )

(defn entity-info-str [w id]
  (let [e (get-e w id)
        en (sort-by #(nth % 0) (vec e))]
    (apply str (interpose "\n " (into [] en)))))

(defn draw-info
  "display info for the cell by abs position: x, y"
  [w [x y :as xy]]
  (let [[x y] (pix->relative [x y])
        abs (relative->absolute [x y])]
    (when (in-viewport? x y)
      ;; (prn :draw-info x y abs)
      (let [cell (m/place w abs)
            ids (:ids cell)
            entities (map #(entity-info-str w %) ids)]
        (q/text (str (abs 0) " " (abs 1) "\n"
                     "form " (:form cell) "\n"
                     "visible " (:visible cell) "\n"
                     "region " (:region cell) "\n"
                     ids "\n"
                     (apply str (interpose "\n" entities)) "\n")
                (pos2pix (inc (vp-width)))
                (pos2pix 1))))))

(defn on-draw
  []
  (let [world @(game :world)
        width (vp-width)
        height (vp-height)
        viewport (viewport)
        mouse-pos @(game :mouse-pos)
        tiles @(:tiles game)
        ]
    (q/background-float (ui :background-color))
    
    ;;(prn tiles)
    
    ;; (q/text-size (ui :text-size))
    (q/text-font (q/state :font-monaco) (ui :text-size))

    (when @(game :paused)
      (q/text "pause" (pos2pix 0) (pos2pix (inc height))))

    (q/text (str :u @(game :update-time)) (pos2pix 3) (pos2pix (inc height)))
    (q/text (str :f (Math/round (q/current-frame-rate))) (pos2pix 6) (pos2pix (inc height)))
    (q/text (str @(game :mouse-action)) (pos2pix 9) (pos2pix (inc height)))
    (q/text (str mouse-pos) (pos2pix 16) (pos2pix (inc height)))

    (draw-world world viewport tiles)

    (q/text-font (q/state :font-monaco) (ui :text-size-small))

    (draw-info world mouse-pos)))



;;; ticks thread

(defn averager [& args]
  (int (/ (apply + args) (count args))))

(defn updating []
  (loop []
    (let [update-sleep-ms (/ 1000 (float (ui :ups-cap)))
          start (timer)
          new-world (if @(:paused game)
                      (:world game)
                      (game! :world on-tick update-sleep-ms))
          elapsed (timer-end start)]

      (game! :update-time averager (/ (float 1000) (max update-sleep-ms elapsed)))
      
      (if (> elapsed update-sleep-ms)
        (prn "timeout:" elapsed)
        (Thread/sleep (- update-sleep-ms elapsed)))
      ;;(Thread/sleep 200)
      (when @running
        (recur))))
  (prn :updating-exited))

;;; quil handlers 

(defn key-press []
  (game! :world on-key (q/raw-key)))

(defn mouse
  "Possible events: :down :up :drag :move :enter :leave."
  [event]
  (game! :world on-mouse (q/mouse-x) (q/mouse-y) event))

(defn setup []
  (game! :world on-start)
  (q/set-state! :font-monaco (q/create-font "Monaco" (ui :text-size) true))
  (reset! (:tiles game) {:grass (q/load-image "/Users/thorn/grass.png")
                         :stone (q/load-image "/Users/thorn/stone.png")
                         :char (q/load-image "/Users/thorn/char.png")})

  (prn @(:tiles game))
  
  (q/hint :enable-depth-test)
  (q/hint :disable-retina-pixels)
  (q/no-smooth)
  (q/image-mode :corner)
  (q/frame-rate (ui :fps-cap))
  (.start (Thread. updating)))

(defn on-close []
  (reset! running false)
  (prn :sketch-exited))

(defn launch []
  (q/sketch
   :title "ECS prototype"
   :size [(ui :window-width) (ui :window-height)]
   :renderer :p2d
   :setup setup
   :draw on-draw
   :key-pressed key-press
   :mouse-pressed #(mouse :down)
   :mouse-moved (fn [] (reset! (game :mouse-pos) [(q/mouse-x) (q/mouse-y)]))
   ;; :mouse-wheel (fn [] (reset! (game :mouse-pos) [(q/mouse-x) (q/mouse-y)]))
   :on-close on-close))


(defonce asset-manager (g/asset-manager))
(g/set-asset-manager! asset-manager)

(defn textures [file]
  (let [sheet (texture file)
        tiles (texture! sheet :split 16 16)
        grass (:object (texture (aget tiles 0 0)))
        stone (:object (texture (aget tiles 0 1)))]
    {:grass grass
     :stone stone}))

(def s (atom {}))

(defn add-renderers [s]
  (assoc s :renderer
         {:shape (ShapeRenderer.)
          :batch (SpriteBatch. 5000)
          :font  (BitmapFont.)
          :textures (textures "tiles.png")}))

(defn render-tick [s]
  (let [r (:renderer s)
        batch (:batch r)
        font (:font r)
        tx (:textures r)
        width (int (/ (float (g/game :width)) 16))
        height (int (/ (float (g/game :height)) 16))
        text (str (g/game :fps) " " (* width height))
        ]
;;    (prn width height (.maxSpritesInBatch batch) (.totalRenderCalls batch))
    (.begin batch)
    (doseq [x (range 1 (dec width))
            y (range 1 (dec height))]
      (.draw batch (if (even? x) (:grass tx) (:stone tx)) (float (* x 16)) (float (* y 16)))
      ;;(.draw font batch "o" (float (* x 16)) (float (* (inc y) 16)))
      )
    (.draw font batch text 10 15)
    (.end batch)))

(g/defscreen main-screen
  :on-show
  (fn [screen _]
    (prn "started")
    (swap! s add-renderers)
    (g/update! screen :renderer (g/stage) :camera (g/orthographic))
    (g/graphics! :set-v-sync false)
    nil)
  
  :on-render
  (fn [screen _]
    (g/clear!)
    (render-tick @s)
    nil))

(g/defgame cg-game
    :on-create
    (fn [this]
      (g/set-screen! this main-screen)))
