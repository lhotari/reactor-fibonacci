#!/bin/bash -e
tmux new-session -d -s profile_with_jfr './gradlew run'
tmux split-window -v 'bash -c "sleep 10; while [ true ]; do time curl 127.0.0.1:8888/20; done"'
tmux split-window -h 'bash -c "echo Waiting 30s until starting to profile; sleep 30; ./gradlew profileJfr; tmux kill-session -t profile_with_jfr"'
tmux attach-session -t profile_with_jfr
jfrfile="$(ls -t *.jfr | head -n 1)"
echo "Finished profiling. Opening $jfrfile"
jmc -open "$jfrfile"
