# Co-play clustering and track recommendations

Clusters tracks that have been played together within 60 minutes with GDS, and recommends other tracks based on those
clusters: by the same artist, by other artists played with a seed track, and by the cluster's artists, their related
artists and the cluster's genres.

`recommend.sh` runs these queries: with `-s` all queries of [Setup](#setup) in order, then the queries of
[Recommendations](#recommendations) for a seed track. It finds each query by its heading, so headings must stay unique,
and only the first `cypher` code block below a heading is used.

The name of the GDS graph is the parameter `$graphName`. GDS graphs are in-memory and gone after a restart, so the setup
has to run again then (`recommend.sh -s`).

## Notes on the virtual graph

- A query that starts with `CALL` can't traverse (no `MATCH` or pattern after it), so a node obtained via
  `gds.util.asNode` can only be used for its own properties. Hence artist and genre ids are stored on each `Track` in
  the projection, as one long array `refs` (`[artistId, genreId]`; every track has exactly one album, so one artist and
  one genre).
- `gds.util.nodeProperty` returns doubles, which corrupts ids (~4.6e18 > 2^53). Ids therefore have to be read from
  `gds.graph.nodeProperty.stream` (exact longs); `gds.util.nodeProperty` is only used for small values (community,
  centrality).
- Results are read back from GDS streams and resolved with `gds.util.asNode` where possible; queries that need names
  for many nodes start from `MATCH` instead, which is much faster.
- No aggregating `WITH`, no `WITH … WHERE`, no expressions in `ORDER BY`. In `MATCH … WHERE`, built-in functions such
  as `toLower` work, but GDS functions such as `gds.util.nodeProperty`, duration arithmetic and id predicates don't.
  Those filters are written as `UNWIND CASE WHEN condition THEN [x] ELSE [] END AS y`.
- No pattern comprehensions (`[(a)-[:RELATED]-(b) | b.name]`).

## Setup

### Drop co-play graph

```cypher
CALL gds.graph.drop($graphName, false) YIELD graphName RETURN graphName
```

### Project tracks and play windows

Bipartite Track-Window graph: every play starts a 60 minute window, and a track belongs to every window it was played
in. Two tracks share a window exactly when they were played within 60 minutes of each other, and the closer they were
played, the more windows they share. `Track` carries `refs = [id(artist), id(genre)]`.

Windows don't exist in the database; they are projected as nodes with the label `Window` and their start in epoch
seconds as id (~1.7e9, no clash with real ids ~4.6e18). Windows don't cross midnight (both plays need the same `Day`),
which affects < 0.1% of the plays. The two `MATCH` clauses are needed so that `q` can be `p` (relationship
uniqueness), otherwise a play doesn't belong to its own window and tracks played alone on a day vanish.

Evaluated on plays from 2025 on, held out from a graph built on older plays: clusters based on these windows put 45%
of the track pairs played in the same hour into the same cluster, with a lift of 127 over chance, vs. 43% and 122 for
clock hours, 42% and 97 for days.

```cypher
MATCH
  (g:Genre)<-[:HAS]-(al:Album)-[:CONTAINS]->(t:Track)-[p:PLAYED_ON]->(d:Day),
  (al)-[:RELEASED_BY]->(ar:Artist)
MATCH (d)<-[q:PLAYED_ON]-(:Track)
WITH
  t,
  p.playedOn AS played,
  q.playedOn AS windowStart,
  id(ar) AS artistId,
  id(g) AS genreId
UNWIND
  CASE
    WHEN
      windowStart <= played AND played < windowStart + duration('PT1H')
      THEN [windowStart]
    ELSE []
  END AS w
WITH DISTINCT
  t,
  datetime({datetime: w, timezone: 'UTC'}).epochSeconds AS window,
  artistId,
  genreId
RETURN
  gds.graph.project(
    $graphName,
    t,
    window,
    {
      sourceNodeLabels: 'Track',
      targetNodeLabels: 'Window',
      relationshipType: 'PLAYED_IN',
      sourceNodeProperties: {refs: [artistId, genreId]},
      targetNodeProperties: null
    },
    {undirectedRelationshipTypes: ['*']}
  ).nodeCount AS nodeCount
```

### Compute co-play similarity

Track-to-track similarity = Jaccard over shared windows (only tracks in >= 2 windows). Only the 5 most similar tracks
are kept per track: a frequently played track is in many windows, which keeps its Jaccard values low; a higher cutoff
instead of a lower `topK` leaves many of these tracks without any neighbour (0.2 / top 10: 9% of the tracks with 10+
plays end up as single-track clusters, vs. 3% with 0.1 / top 5).

The filtered variant compares tracks only; the unfiltered one also compares all windows with each other, which takes
~20x as long.

```cypher
CALL gds.nodeSimilarity.filtered.mutate($graphName, {
  nodeLabels: ['Track', 'Window'], relationshipTypes: ['PLAYED_IN'],
  sourceNodeFilter: 'Track', targetNodeFilter: 'Track',
  degreeCutoff: 2, topK: 5, similarityCutoff: 0.1,
  mutateRelationshipType: 'CO_PLAYED', mutateProperty: 'score'})
YIELD nodesCompared, relationshipsWritten
RETURN nodesCompared, relationshipsWritten
```

### Make co-play relationships undirected

Leiden needs undirected relationships, node similarity writes directed ones.

```cypher
CALL gds.graph.relationships.toUndirected($graphName, {
  relationshipType: 'CO_PLAYED', mutateRelationshipType: 'CO_PLAYED_UNDIRECTED'})
YIELD relationshipsWritten
RETURN relationshipsWritten
```

### Cluster co-played tracks

Clusters of tracks that are played together within 60 minutes. Leiden instead of Louvain for its resolution parameter
`gamma` (GDS Louvain has none). `gamma` 8 halves the size of the largest clusters compared to `gamma` 1 (or Louvain),
while the share of held-out same-hour pairs in the same cluster only drops from 44.7% to 44.4% (lift over chance 139
instead of 127). Higher values give still smaller clusters, but lose co-plays faster (`gamma` 32: 43.6%).

```cypher
CALL gds.leiden.mutate($graphName, {
  nodeLabels: ['Track'], relationshipTypes: ['CO_PLAYED_UNDIRECTED'],
  relationshipWeightProperty: 'score', gamma: 8.0, randomSeed: 42, mutateProperty: 'community'})
YIELD communityCount, modularity
RETURN communityCount, modularity
```

### Rank tracks by co-play centrality

How central a track is in the co-play graph (PageRank), used together with the play count to rank the recommendations
from the cluster's artists and genres.

```cypher
CALL gds.pageRank.mutate($graphName, {
  nodeLabels: ['Track'], relationshipTypes: ['CO_PLAYED'],
  relationshipWeightProperty: 'score', mutateProperty: 'centrality'})
YIELD ranIterations
RETURN ranIterations
```

### List largest clusters

Overview of the largest clusters. Starts from `MATCH` instead of the GDS stream: resolving every track's artist and
genre via `gds.util.asNode` is ~30x slower.

```cypher
MATCH (g:Genre)<-[:HAS]-(al:Album)-[:CONTAINS]->(t:Track), (al)-[:RELEASED_BY]->(ar:Artist)
WITH t, ar, g, gds.util.nodeProperty($graphName, t, 'community', 'Track') AS cluster
RETURN toInteger(cluster) AS cluster, count(*) AS size,
       count(DISTINCT ar) AS artists,
       collect(DISTINCT ar.name)[0..6] AS someArtists,
       collect(DISTINCT g.name)[0..4] AS someGenres
ORDER BY size DESC LIMIT 20
```

## Recommendations

Recommendations for a seed track, based on the GDS graph from the setup. Assumes every track has a unique id in the
mapping (`gds.util.asNode` fails on ids that occur more than once). Cluster ids change on every clustering run, so look
them up after (re)running the setup.

The virtual graph doesn't allow chaining these steps in one query (no aggregating `WITH`, nothing after `CALL` can
traverse), so each step's result is a parameter of the next one. Output of `cypher-shell --format plain` is a valid
Cypher literal and can be pasted into `:param`. To run them by hand, start with

```
:param {graphName: 'recommendations', track: 'Stairway To Heaven', artist: 'Led Zeppelin'}
```

### Check co-play graph exists

```cypher
CALL gds.graph.exists($graphName) YIELD exists RETURN exists
```

### Find seed cluster

Cluster of the seed track (the most played version, if it exists on several albums). Artist and track are compared
case-insensitively. The cluster is the parameter `$cluster` of the following steps, and the names as stored in the
database are the parameters `$artist` and `$track`, so that the following steps can compare them exactly.

```cypher
MATCH (a:Artist)<-[:RELEASED_BY]-(:Album)-[:CONTAINS]->(seed:Track)
WHERE toLower(a.name) = toLower($artist) AND toLower(seed.name) = toLower($track)
RETURN toInteger(gds.util.nodeProperty($graphName, seed, 'community', 'Track')) AS cluster, seed.playedCount AS plays,
       a.name AS artist, seed.name AS track
ORDER BY plays DESC LIMIT 1
```

### Profile seed cluster

Artists and genres of the cluster, weighted by number of tracks in the cluster. Keys are ids as strings (map keys must
be strings); the `*Names` columns are for reading. The first two maps are the parameters `$artists` and `$genres` of
[Recommend by cluster artists and genres](#recommend-by-cluster-artists-and-genres).

```cypher
CALL gds.graph.nodeProperty.stream($graphName, 'refs', ['Track']) YIELD nodeId, propertyValue AS refs
WITH refs, gds.util.nodeProperty($graphName, nodeId, 'community', 'Track') AS c
UNWIND CASE WHEN c = $cluster THEN [refs] ELSE [] END AS r
RETURN apoc.coll.frequenciesAsMap(collect(toString(r[0]))) AS artists,
       apoc.coll.frequenciesAsMap(collect(toString(r[1]))) AS genres,
       apoc.coll.frequenciesAsMap(collect(gds.util.asNode(r[0]).name)) AS artistNames,
       apoc.coll.frequenciesAsMap(collect(gds.util.asNode(r[1]).name)) AS genreNames
```

### Find artist's top clusters

The artist's top 3 clusters overall, by plays of the artist's tracks in them. The list of these clusters is the
parameter `$seedClusters` of the next step.

```cypher
MATCH (a:Artist)<-[:RELEASED_BY]-(:Album)-[:CONTAINS]->(t:Track)
WHERE a.name = $artist
WITH t, toInteger(gds.util.nodeProperty($graphName, t, 'community', 'Track')) AS cluster
RETURN cluster, sum(t.playedCount) AS plays, count(*) AS tracks
ORDER BY plays DESC LIMIT 3
```

### Find related artists of cluster

Artists `RELATED` to the cluster's artists, without the cluster's artists themselves. `RELATED` is stored in both
directions, so following it outgoing finds each related artist once per cluster artist. Each related artist is weighted
by the tracks in the cluster of the artists it's related to, summed up. `apoc.coll.frequenciesAsMap` does the summing,
because a map can't be built from aggregated values without an aggregating `WITH`. The first map is the parameter
`$related` of [Recommend by cluster artists and genres](#recommend-by-cluster-artists-and-genres). It's `{}` when no
artist of the cluster has related artists.

```cypher
MATCH (a:Artist)-[:RELATED]->(r:Artist)
WITH r, toString(id(a)) AS artistKey, toString(id(r)) AS relatedKey
UNWIND
  CASE
    WHEN
      artistKey IN keys($artists) AND NOT relatedKey IN keys($artists)
      THEN range(1, $artists[artistKey])
    ELSE []
  END AS x
RETURN
  apoc.coll.frequenciesAsMap(collect(relatedKey)) AS related,
  apoc.coll.frequenciesAsMap(collect(r.name)) AS relatedNames
```

### Recommend artist's tracks from top clusters

Tracks by the same artist in the artist's top 3 clusters.

```cypher
MATCH (a:Artist)<-[:RELEASED_BY]-(al:Album)-[:CONTAINS]->(t:Track)
WHERE a.name = $artist
WITH al, t, toInteger(gds.util.nodeProperty($graphName, t, 'community', 'Track')) AS cluster
UNWIND CASE WHEN cluster IN $seedClusters AND t.name <> $track THEN [t] ELSE [] END AS rec
RETURN rec.name AS track, sum(rec.playedCount) AS plays, collect(DISTINCT al.name) AS albums, collect(DISTINCT cluster) AS clusters
ORDER BY plays DESC LIMIT 15
```

### Recommend tracks played with seed

Tracks by other artists that were played within 60 minutes of the seed (any version of it), top 3 per artist by how
often. Reads the plays directly instead of the clusters: the clusters are tight (often a single album session), so for
~70% of the tracks with 10+ plays the seed's cluster has no other artist, and personalized PageRank on the similarity
graph stays on the seed's island as well.

On held-out plays from 2025 on, this step plus the next one to fill up the list finds a track played within 60 minutes
of the seed for 10.5% of the seeds, vs. 5.8% for the other artists in the seed's cluster plus the next step (9.5% for
personalized PageRank on the Track-Window graph plus the next step).

```cypher
MATCH (a:Artist)<-[:RELEASED_BY]-(:Album)-[:CONTAINS]->(seed:Track)-[p:PLAYED_ON]->(d:Day)
WHERE a.name = $artist AND seed.name = $track
MATCH (d)<-[q:PLAYED_ON]-(rec:Track)<-[:CONTAINS]-(:Album)-[:RELEASED_BY]->(ar:Artist)
WITH ar, rec, p.playedOn - duration('PT1H') < q.playedOn AND q.playedOn < p.playedOn + duration('PT1H') AS near
UNWIND
  CASE
    WHEN near AND ar.name <> $artist THEN [rec.name]
    ELSE []
  END AS candidate
RETURN
  ar.name AS artist,
  count(*) AS coPlays,
  apoc.coll.sortMaps(apoc.coll.frequencies(collect(candidate)), 'count')[0].count AS bestTrackCoPlays,
  [
    m IN
    apoc.coll.sortMaps(apoc.coll.frequencies(collect(candidate)), 'count')[0..3]
    | m.item
  ] AS recommendations
ORDER BY bestTrackCoPlays DESC, coPlays DESC
LIMIT 20
```

### Recommend by cluster artists and genres

Tracks outside the cluster, top 3 per artist: by the cluster's artists, by artists related to them, and by other
artists in the cluster's genres. Score = (tracks of the artist in the cluster + related weight of the artist + tracks of
the genre in the cluster) * play count of the track * square root of its PageRank in the co-play graph.

The artists are ranked by their best score; `tier` (1 the cluster's artists, 2 related artists, 3 others in the
cluster's genres) only breaks ties, in favor of the higher tier, so on equal scores artists further away from the
cluster come first. Related artists get their weight on top of the genre's, so they rank above the other artists of the
same genre with the same centrality, and they are recommended even when they play a different genre.

On plays from 2025 on, held out from a graph built on older plays (with play counts as of 2025), this finds a track
played within 60 minutes of the seed for 13.5% of the seeds, vs. 3.3% for PageRank alone and 16.6% for the play count
alone. The play count rewards popular tracks, which this evaluation favors (the most played tracks overall, regardless
of the cluster, reach 11.8%); the square root of PageRank keeps some weight on how central a track is in the co-play
graph. Weighting the cluster's artists and genres by plays instead of tracks makes no difference.

Starts from `MATCH` instead of the GDS stream: resolving every candidate via `gds.util.asNode` is ~40x slower. Ids
can't be compared in `WHERE` on the virtual graph, hence the `UNWIND` filter. A track on several albums (e.g. a single
and a compilation) is recommended once, with its best score: `apoc.coll.toSet` keeps the first occurrence of each name.

```cypher
MATCH
  (g:Genre)<-[:HAS]-(al:Album)-[:CONTAINS]->(rec:Track),
  (al)-[:RELEASED_BY]->(ar:Artist)
WITH
  g, ar, rec,
  toString(id(g)) AS genreKey,
  toString(id(ar)) AS artistKey,
  gds.util.nodeProperty($graphName, rec, 'community', 'Track') AS c
UNWIND
  CASE
    WHEN
      c <> $cluster AND
      (artistKey IN keys($artists) OR
        artistKey IN keys($related) OR
        genreKey IN keys($genres))
      THEN [rec]
    ELSE []
  END AS candidate
WITH
  ar, candidate,
  CASE
    WHEN artistKey IN keys($artists) THEN 'artist in cluster'
    WHEN artistKey IN keys($related) THEN 'related artist'
    ELSE 'genre ' + g.name
  END AS reason,
  (coalesce($artists[artistKey], 0) +
    coalesce($related[artistKey], 0) +
    coalesce($genres[genreKey], 0)) *
  candidate.playedCount *
  sqrt(gds.util.nodeProperty($graphName, candidate, 'centrality', 'Track')) AS score
RETURN
  round(max(score), 1) AS bestScore,
  ar.name AS artist,
  apoc.coll.toSet(
    [
      m IN apoc.coll.sortMaps(collect({track: candidate.name, score: score}), 'score')
      | m.track
    ]
  )[0..3] AS recommendations,
  reason
ORDER BY bestScore DESC, reason DESC
LIMIT 20
```
