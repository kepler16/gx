default:
    @just --choose

build *args:
    clojure -T:build build {{args}}

release *args:
    clojure -T:build release {{args}}

test *args:
    clojure -M:test
