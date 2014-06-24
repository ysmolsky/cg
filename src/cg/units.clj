(ns cg.units
  "Functions for adding new entities into the world"
  (:require [cg.comps :refer :all]
            [cg.ecs :refer :all]))

(defn add-job [w kind x y char]
  (load-entity w kind
               (job kind)
               (position x y)
               (renderable char)
               (free)))

(defn add-stone [w x y]
  (load-entity w :stone
               (stone :gabbro)
               (position x y)
               (renderable "✶")))

(defn add-player [w [x y]]
  (load-entity w :unit
               (speed 10)
               (position (float x) (float y))
               (controllable)
               (renderable :char)
               (job-ready)))

