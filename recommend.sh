#!/usr/bin/env bash
#
# Recommends tracks based on the co-play cluster of a seed track.
# Runs the queries of queries.md (next to this script), which it finds by their headings; requires a
# GDS graph created by the queries in the Setup section of queries.md, which this script runs first
# when called with -s.
#
# Usage: recommend.sh [-a bolt-url] [-u username] [-p password] [-g graph-name] [-s] <artist> <track>
#   e.g. recommend.sh -a bolt://localhost:7687 -u neo4j -p verysecret -s "Led Zeppelin" "Stairway To Heaven"
#
#   -g  name of the GDS graph (default: recommendations)
#   -s  (re)create the GDS graph by running all queries of the Setup section of queries.md first
#
# Defaults: bolt://localhost:7687, user neo4j; the password can also come from NEO4J_PASSWORD.

set -euo pipefail

usage() {
  echo "Usage: $(basename "$0") [-a bolt-url] [-u username] [-p password] [-g graph-name] [-s] <artist> <track>" >&2
  exit 1
}

address="${NEO4J_URI:-bolt://localhost:7687}"
username="${NEO4J_USERNAME:-neo4j}"
password="${NEO4J_PASSWORD:-}"
graph_name="recommendations"
run_setup=false
queries_file="$(cd "$(dirname "$0")" && pwd)/queries.md"

while getopts "a:u:p:g:sh" opt; do
  case "$opt" in
    a) address="$OPTARG" ;;
    u) username="$OPTARG" ;;
    p) password="$OPTARG" ;;
    g) graph_name="$OPTARG" ;;
    s) run_setup=true ;;
    *) usage ;;
  esac
done
shift $((OPTIND - 1))
[[ $# -eq 2 ]] || usage
[[ -n "$password" ]] || { echo "No password given (-p or NEO4J_PASSWORD)" >&2; exit 1; }
command -v cypher-shell >/dev/null || { echo "cypher-shell not found on PATH" >&2; exit 1; }
[[ -r "$queries_file" ]] || { echo "Queries $queries_file not found" >&2; exit 1; }

artist="$1"
track="$2"

# Prints the first cypher code block below the heading "### <name>" of queries.md
query() {
  local q
  q=$(awk -v heading="### $1" '
    $0 == heading { found = 1; next }
    found && /^```cypher[[:space:]]*$/ { in_block = 1; next }
    in_block && /^```/ { exit }
    in_block { print }
  ' "$queries_file")
  [[ -n "$q" ]] || { echo "Query \"$1\" not found in $queries_file" >&2; exit 1; }
  printf '%s\n' "$q"
}

# Prints all cypher code blocks of the section "## <name>" of queries.md, in order, each terminated by a semicolon
# (needed to pipe them into cypher-shell as one script) unless it already ends with one
section_queries() {
  awk -v heading="## $1" '
    /^## / { in_section = ($0 == heading); next }
    in_section && /^```cypher[[:space:]]*$/ { in_block = 1; last = ""; next }
    in_block && /^```/ { in_block = 0; if (last !~ /;[[:space:]]*$/) print ";"; print ""; next }
    in_section && in_block { print; if ($0 ~ /[^[:space:]]/) last = $0 }
  ' "$queries_file"
}

# Turns a bash string into a Cypher string literal
cypher_string() {
  printf "'%s'" "$(printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e "s/'/\\\\'/g")"
}

# Turns a Cypher string literal from cypher-shell --format plain back into a bash string (for display)
unquote() {
  printf '%s' "$1" | sed -e 's/^"//' -e 's/"$//' -e 's/\\\(.\)/\1/g'
}

graph_param="{graphName: $(cypher_string "$graph_name")}"

# Runs the query named $1 with $graphName set; remaining args are passed to cypher-shell (e.g. --format, -P).
# The query is passed as a single argument, so its $parameters reach cypher-shell unexpanded.
cypher() {
  local q
  q=$(query "$1") || exit 1
  shift
  cypher-shell -a "$address" -u "$username" -p "$password" -P "$graph_param" "$@" "$q"
}

if [[ "$run_setup" == true ]]; then
  setup=$(section_queries "Setup")
  [[ -n "$setup" ]] || { echo "No setup queries found in $queries_file" >&2; exit 1; }
  echo "Creating GDS graph '$graph_name' with the setup queries of $(basename "$queries_file") (takes about 45 seconds)..."
  printf '%s\n' "$setup" | cypher-shell -a "$address" -u "$username" -p "$password" -P "$graph_param" --format verbose
  echo
fi

graph_exists=$(cypher "Check co-play graph exists" --format plain | tail -n 1)
if [[ "$graph_exists" != "TRUE" ]]; then
  echo "GDS graph '$graph_name' not found, run with -s to create it." >&2
  exit 1
fi

seed=$(cypher "Find seed cluster" --format plain \
  -P "{artist: $(cypher_string "$artist"), track: $(cypher_string "$track")}" | tail -n +2)
if [[ -z "$seed" ]]; then
  echo "Track \"$track\" by \"$artist\" not found." >&2
  exit 1
fi
# cluster, plays, artist, track; the names are Cypher string literals (inner quotes escaped) with the
# spelling from the database, used as parameters from here on so that the other queries can compare exactly
seed_regex='^([0-9-]+), ([0-9]+), ("([^"\\]|\\.)*"), ("([^"\\]|\\.)*")$'
[[ "$seed" =~ $seed_regex ]] || { echo "Unexpected seed: $seed" >&2; exit 1; }
cluster="${BASH_REMATCH[1]}"
plays="${BASH_REMATCH[2]}"
seed_params="{artist: ${BASH_REMATCH[3]}, track: ${BASH_REMATCH[5]}}"
artist=$(unquote "${BASH_REMATCH[3]}")
track=$(unquote "${BASH_REMATCH[5]}")
echo "Seed: \"$track\" by $artist ($plays plays), cluster $cluster"

artist_clusters=$(cypher "Find artist's top clusters" --format plain -P "$seed_params" | tail -n +2)
cluster_list=$(echo "$artist_clusters" | cut -d, -f1 | paste -sd, - | sed 's/,/, /g')
echo "Top clusters of $artist:"
echo "$artist_clusters" | while IFS=', ' read -r c p n; do
  echo "  cluster $c: $p plays ($n track(s))"
done

echo
echo "More by $artist, from the artist's top clusters:"
cypher "Recommend artist's tracks from top clusters" --format verbose -P "$seed_params" -P "{seedClusters: [$cluster_list]}"

# The first two maps are keyed by ids (no braces inside), the last two by names
profile=$(cypher "Profile seed cluster" --format plain -P "{cluster: $cluster}" | tail -n 1)
maps_regex='^(\{[^}]*\}), (\{[^}]*\}), (.*)$'
[[ "$profile" =~ $maps_regex ]] || { echo "Unexpected cluster profile: $profile" >&2; exit 1; }
artists="${BASH_REMATCH[1]}"
genres="${BASH_REMATCH[2]}"
echo "Cluster profile (artists, genres): ${BASH_REMATCH[3]}"

echo
echo "Played with \"$track\" (within 60 minutes):"
cypher "Recommend tracks played with seed" --format verbose -P "$seed_params"

# The first map is keyed by ids, the second by names
related_artists=$(cypher "Find related artists of cluster" --format plain -P "{artists: $artists}" | tail -n 1)
related_regex='^(\{[^}]*\}), (.*)$'
[[ "$related_artists" =~ $related_regex ]] || { echo "Unexpected related artists: $related_artists" >&2; exit 1; }
related="${BASH_REMATCH[1]}"
echo "Related artists of the cluster: ${BASH_REMATCH[2]}"

echo
echo "Recommendations from the cluster's artists, their related artists and the cluster's genres:"
cypher "Recommend by cluster artists and genres" --format verbose \
  -P "{cluster: $cluster, artists: $artists, related: $related, genres: $genres}"
