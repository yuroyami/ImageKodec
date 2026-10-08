#!/usr/bin/env bash
# Runs one test command as several processes at once. Each process takes its own share of the
# fuzz suite, so the suite ends in a fraction of the time on a machine with several cores.
#
#   tools/run-shards.sh <shards> <command> [argument...]
#
# <shards> is a count, such as 4: the command runs four times here, as shards 0 to 3 of 4.
# It can also be a range of a count, such as 2-3/8: the command runs twice here, as shards 2
# and 3 of 8, and other machines run the other six.
#
# Shard i of <count> runs with IMAGEKODEC_FUZZ_SHARD=i/<count>, which FuzzShard.kt reads.
# Every process runs all the other tests too. They take a few seconds.
#
# The script fails when:
#   - a process exits with an error;
#   - a process prints a TeamCity "testFailed" message (the Kotlin/Wasm test runners report a
#     failure that way and still exit with 0);
#   - a process reports no test at all, which means the command did not reach the tests.
set -u

usage() {
  echo "usage: $0 <count | first-last/count> <command> [argument...]" >&2
  exit 2
}

[ "$#" -ge 2 ] || usage
if [[ $1 =~ ^([0-9]+)$ ]]; then
  count=$1; first=0; last=$((count - 1))
elif [[ $1 =~ ^([0-9]+)-([0-9]+)/([0-9]+)$ ]]; then
  first=${BASH_REMATCH[1]}; last=${BASH_REMATCH[2]}; count=${BASH_REMATCH[3]}
else
  usage
fi
shift
[ "$first" -le "$last" ] && [ "$last" -lt "$count" ] || usage

logs=$(mktemp -d)
i=$first
while [ "$i" -le "$last" ]; do
  (
    # `xcrun simctl spawn` hands a variable to the simulated process only with this prefix.
    export IMAGEKODEC_FUZZ_SHARD="$i/$count" SIMCTL_CHILD_IMAGEKODEC_FUZZ_SHARD="$i/$count"
    SECONDS=0
    "$@" > "$logs/$i.log" 2>&1
    echo "$? $SECONDS" > "$logs/$i.status"
  ) &
  i=$((i + 1))
done
wait

failed=0
i=$first
while [ "$i" -le "$last" ]; do
  log="$logs/$i.log"
  code=1
  seconds=0
  [ -f "$logs/$i.status" ] && read -r code seconds < "$logs/$i.status"
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
    echo "::error::shard $i/$count $problem after $seconds s"
    echo "----- shard $i/$count: $problem -----"
    cat "$log"
  else
    echo "::group::shard $i/$count passed in $seconds s"
    # A passing log is long and holds nothing to act on; its last lines show what ran.
    tail -n 15 "$log"
    echo "::endgroup::"
  fi
  i=$((i + 1))
done
exit "$failed"
