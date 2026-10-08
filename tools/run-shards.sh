#!/usr/bin/env bash
# Runs one test command as several processes at once. Each process takes its own share of the
# fuzz suite, so the suite ends in a fraction of the time on a machine with several cores.
#
#   tools/run-shards.sh <count> <command> [argument...]
#
# Process i of <count> runs with IMAGEKODEC_FUZZ_SHARD=i/<count>, which FuzzShard.kt reads.
# Every process runs all the other tests too. They take a few seconds.
#
# The script fails when:
#   - a process exits with an error;
#   - a process prints a TeamCity "testFailed" message (the Kotlin/Wasm test runners report a
#     failure that way and still exit with 0);
#   - a process reports no test at all, which means the command did not reach the tests.
set -u

if [ "$#" -lt 2 ]; then
  echo "usage: $0 <count> <command> [argument...]" >&2
  exit 2
fi
count=$1
shift

logs=$(mktemp -d)
pids=()
i=0
while [ "$i" -lt "$count" ]; do
  # `xcrun simctl spawn` hands a variable to the simulated process only with this prefix.
  IMAGEKODEC_FUZZ_SHARD="$i/$count" SIMCTL_CHILD_IMAGEKODEC_FUZZ_SHARD="$i/$count" \
    "$@" > "$logs/$i.log" 2>&1 &
  pids+=("$!")
  i=$((i + 1))
done

failed=0
i=0
while [ "$i" -lt "$count" ]; do
  wait "${pids[$i]}"
  code=$?
  log="$logs/$i.log"
  problem=""
  if [ "$code" -ne 0 ]; then
    problem="exited with $code"
  elif grep -q "##teamcity\[testFailed" "$log"; then
    problem="reported a failed test"
  elif ! grep -qE "^\[       OK \]|##teamcity\[testFinished|^ +[0-9]+ passing" "$log"; then
    problem="reported no test"
  fi
  if [ -n "$problem" ]; then
    failed=1
    echo "::error::shard $i/$count $problem"
    echo "----- shard $i/$count: $problem -----"
    cat "$log"
  else
    echo "::group::shard $i/$count passed"
    # A passing log is long and holds nothing to act on; its last lines show what ran.
    tail -n 15 "$log"
    echo "::endgroup::"
  fi
  i=$((i + 1))
done
exit "$failed"
