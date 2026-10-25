package recommendations;

import module org.commonmark;
import module org.neo4j.jdbc;
import module tagtraum.obstmusic;
import module info.picocli;

import java.io.FileReader;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

@CommandLine.Command(name = "recommend", mixinStandardHelpOptions = true,
		description = "Provides musical recommendations")
public final class Application implements Callable<Integer> {

	enum Playlist {

		BY_CLUSTER, BY_PLAYED_TOGETHER, BY_CLUSTER_AND_RELATED_ARTISTS_AND_GENRE, NONE

	}

	enum Pick {

		BY_HIGHEST_SCORE, BY_MEDIUM_SCORE, BY_LOWEST_SCORE, BY_RANDOM_SCORE

	}

	@CommandLine.Option(names = { "-a", "--address" }, description = "The Neo4j instance this program should.",
			required = true, defaultValue = "jdbc:neo4j://localhost:7687")
	private URI address;

	@CommandLine.Option(names = { "-u", "--username" },
			description = "The login of the user connecting to the database.", required = true, defaultValue = "neo4j")
	private String user;

	@CommandLine.Option(names = { "-p", "--password" },
			description = "The password of the user connecting to the database.", interactive = true, required = true)
	private char[] password;

	@CommandLine.Option(names = { "-g", "--graph" }, description = "The name of the GDS graph", required = true,
			defaultValue = "recommendations")
	private String graphName;

	@CommandLine.Option(names = "-s",
			description = "(re)create the GDS graph by running all queries of the Setup section of queries.md first",
			defaultValue = "false")
	private boolean runSetup;

	@CommandLine.Option(names = { "--target-folder" }, description = "The name of the target playlist folder",
			required = true, defaultValue = "Recommendations")
	private String targetFolder;

	@CommandLine.Option(names = { "--create-playlist" }, description = "Playlist type to create", required = true,
			defaultValue = "NONE")
	private Playlist createPlaylist;

	@CommandLine.Option(names = { "--pick-track-by" },
			description = "If recommendations contains more than one track per row pick one by the given score",
			required = true, defaultValue = "BY_RANDOM_SCORE")
	private Pick pick;

	@CommandLine.Parameters(index = "0")
	private String artist;

	@CommandLine.Parameters(index = "1")
	private String track;

	static void main(String... args) {

		var commandLine = new CommandLine(new Application());
		commandLine.setCaseInsensitiveEnumValuesAllowed(true);

		System.exit(commandLine.execute(args));
	}

	@Override
	public Integer call() {
		try (var in = new FileReader(Path.of("queries.md").toFile());
				var connection = DriverManager.getConnection(this.address.toString(), this.user,
						new String(this.password))) {
			var queryExtractor = new QueryExtractor();
			var markdownParser = Parser.builder().build();

			var node = markdownParser.parseReader(in);
			node.accept(queryExtractor);

			var recommendations = new Recommendations(connection, queryExtractor.recommendations, this.graphName);
			if (!(this.runSetup || recommendations.graphExists())) {
				System.err.printf("GDS graph '%s' not found, run with -s to create it.%n", this.graphName);
				return 2;
			}

			if (this.runSetup) {
				var setup = new Setup(connection, queryExtractor.setup, this.graphName);
				setup.createGraph();
			}

			var cluster = recommendations.loadSeedCluster(this.artist, this.track);
			if (cluster == null) {
				System.err.printf("Track \"%s\" by \"%s\" not found.%n", this.track, this.artist);
				return 2;
			}
			System.out.printf("Seed: \"%s\" by %s (%d plays), cluster %d%nArtists: %s,%nGenres: %s%n",
					cluster.seedTrack.artist, cluster.seedTrack.name, cluster.plays, cluster.cluster,
					cluster.artistsNames, cluster.genresNames);

			var related = recommendations.findRelatedArtists();
			System.out.println("Related artists of this cluster:");
			related.relatedNames.forEach((name, freq) -> System.out.printf("%s (%d)%n", name, freq.longValue()));

			System.out.printf("%nTop clusters of %s:%n", cluster.seedTrack.artist);
			var topClusters = recommendations.findTopClusters();
			topClusters.forEach(clusterDetails -> System.out.printf("cluster %d: %d plays (%d track(s))%n",
					clusterDetails.cluster, clusterDetails.plays, clusterDetails.tracks));

			var byCluster = recommendations.byCluster(topClusters);
			var byPlayedTogether = recommendations.byPlayedTogether();
			var byClusterAndRelatedArtistsAndGenre = recommendations.byClusterAndRelatedArtistsAndGenre(related);

			switch (this.createPlaylist) {
				case BY_CLUSTER:
					createPlaylist(cluster.seedTrack, null, "track", byCluster);
					break;

				case BY_PLAYED_TOGETHER:
					createPlaylist(cluster.seedTrack, "artist", "recommendations", byPlayedTogether);
					break;
				case BY_CLUSTER_AND_RELATED_ARTISTS_AND_GENRE:
					createPlaylist(cluster.seedTrack, "artist", "recommendations", byClusterAndRelatedArtistsAndGenre);
					break;
			}
		}
		catch (Exception ex) {
			System.err.println(ex.getMessage());
			return 1;
		}
		return 0;
	}

	void createPlaylist(Track track, String columnArtist, String columnTrack, Map<String, List<Object>> src) {

		var root = Logger.getLogger("com.tagtraum.japlscript");
		root.setLevel(Level.OFF);
		for (var handler : root.getHandlers()) {
			handler.setLevel(Level.OFF);
		}

		var random = ThreadLocalRandom.current();
		var session = JaplScript.startSession();
		var appleMusic = com.tagtraum.macos.music.Application.getInstance();
		var library = new ReferenceImpl("library playlist 1", appleMusic.getApplicationReference())
			.cast(LibraryPlaylist.class);
		var target = new ReferenceImpl("folder playlist %s".formatted(JaplScript.quote(this.targetFolder)),
				appleMusic.getApplicationReference())
			.cast(FolderPlaylist.class);
		var live = new ReferenceImpl("user playlist %s".formatted(JaplScript.quote("Live")),
				appleMusic.getApplicationReference())
			.cast(UserPlaylist.class);
		var newPlaylist = appleMusic.make(UserPlaylist.CLASS, target,
				Map.of("name", new ReferenceImpl(JaplScript.quote("%s %s".formatted(LocalDate.now(), track.name)),
						appleMusic.getApplicationReference())));

		int cnt = 0;
		for (var item : src.get(columnTrack)) {
			var artist = track.artist;
			if (columnArtist != null) {
				artist = (String) src.get(columnArtist).get(cnt);
			}
			String trackName = null;
			if (item instanceof String v) {
				trackName = v;
			}
			else if (item instanceof List<?> tracks) {
				if (tracks.size() == 3) {
					trackName = (String) tracks.get(switch (this.pick) {
						case BY_HIGHEST_SCORE -> 0;
						case BY_MEDIUM_SCORE -> 1;
						case BY_LOWEST_SCORE -> 2;
						case BY_RANDOM_SCORE -> random.nextInt(0, 3);
					});
				}
				else if (!tracks.isEmpty()) {
					trackName = (String) tracks.getFirst();
				}
			}

			if (trackName == null) {
				continue;
			}
			var tracks = library.cast(LibraryPlaylist.class)
				.getFileTracks(
						"artist is %s and name is %s".formatted(JaplScript.quote(artist), JaplScript.quote(trackName)));
			Arrays.stream(tracks)
				.filter(t -> !t.isCompilation()
						&& live.countFileTracks("database ID is %d".formatted(t.getDatabaseId())) == 0)
				.findFirst()
				.ifPresent(t -> appleMusic.duplicate(t, newPlaylist));
			++cnt;
		}

		session.commit();
	}

	private static Map<String, List<Object>> printAndTabularizeResultSet(ResultSet resultSet) throws SQLException {

		var table = new LinkedHashMap<String, List<Object>>();
		var meta = resultSet.getMetaData();
		var numRows = 0;
		while (resultSet.next()) {
			for (int i = 1; i <= meta.getColumnCount(); ++i) {
				var column = table.computeIfAbsent(meta.getColumnLabel(i), _ -> new ArrayList<>());
				column.add(resultSet.getObject(i));
			}
			++numRows;
		}
		if (numRows == 0) {
			return Map.of();
		}

		var columnWidths = new int[table.size()];
		var columnIndex = 0;
		for (var entry : table.entrySet()) {
			var k = entry.getKey();
			var v = entry.getValue();
			columnWidths[columnIndex++] = Math.max(k.length(),
					v.stream().map(Object::toString).map(String::length).max(Integer::compareTo).orElse(0));
		}
		var columnFormats = Arrays.stream(columnWidths).mapToObj(w -> "| %-" + w + "s ").toArray(String[]::new);

		var separator = new StringBuilder();
		for (int columnWidth : columnWidths) {
			separator.append("+");
			separator.repeat("-", Math.max(0, columnWidth + 2));
		}
		separator.append("+");

		var rows = new TreeMap<Integer, StringBuilder>();
		columnIndex = 0;
		for (var v : table.values()) {
			for (int i = 0; i < numRows; ++i) {
				StringBuilder row = rows.computeIfAbsent(i, _ -> new StringBuilder());
				row.append(String.format(columnFormats[columnIndex], v.get(i)));
			}
			++columnIndex;
		}

		System.out.println(separator);
		columnIndex = 0;
		for (String column : table.keySet()) {
			System.out.printf((columnFormats[columnIndex++]), column);
		}
		System.out.println("|");
		System.out.println(separator);
		rows.values().forEach(row -> System.out.printf("%s%s%n", row, "|"));
		System.out.println(separator);
		System.out.println();

		return table;
	}

	private static final class Setup {

		private final Connection connection;

		private final Map<String, String> queries;

		private final String graphName;

		Setup(Connection connection, Map<String, String> queries, String graphName) {
			this.connection = connection;
			this.queries = queries;
			this.graphName = graphName;
		}

		void createGraph() throws SQLException {
			for (Map.Entry<String, String> entry : this.queries.entrySet()) {
				System.out.println(entry.getKey());
				try (var stmt = this.connection.prepareStatement(entry.getValue())
					.unwrap(Neo4jPreparedStatement.class)) {
					stmt.setString("graphName", this.graphName);
					printAndTabularizeResultSet(stmt.executeQuery());
				}
			}
		}

	}

	private static final class Recommendations {

		private final Connection connection;

		private final Map<String, String> queries;

		private final String graphName;

		private Cluster cluster;

		Recommendations(Connection connection, Map<String, String> queries, String graphName) {
			this.connection = connection;
			this.queries = queries;
			this.graphName = graphName;
		}

		private Neo4jPreparedStatement newStatement(String queryName) throws SQLException {
			return this.connection.prepareStatement(this.queries.get(queryName)).unwrap(Neo4jPreparedStatement.class);
		}

		boolean graphExists() throws SQLException {
			try (var stmt = newStatement("Check co-play graph exists")) {
				stmt.setString("graphName", this.graphName);
				var rs = stmt.executeQuery();
				return rs.next() && rs.getBoolean("exists");
			}
		}

		@SuppressWarnings("unchecked")
		Cluster loadSeedCluster(String artist, String track) throws SQLException {
			this.connection.setAutoCommit(false);
			try (var findStmt = newStatement("Find seed cluster");
					var profileStmt = newStatement("Profile seed cluster")) {
				findStmt.setString("graphName", this.graphName);
				findStmt.setString("artist", artist);
				findStmt.setString("track", track);

				var rs = findStmt.executeQuery();
				if (rs.next()) {
					Map<String, Number> artists = new HashMap<>();
					Map<String, Number> artistNames = new HashMap<>();
					Map<String, Number> genres = new HashMap<>();
					Map<String, Number> genresNames = new HashMap<>();

					var cluster = rs.getInt("cluster");

					profileStmt.setString("graphName", this.graphName);
					profileStmt.setInt("cluster", cluster);

					var inner = profileStmt.executeQuery();
					if (inner.next()) {
						artists = (Map<String, Number>) inner.getObject("artists", Map.class);
						artistNames = (Map<String, Number>) inner.getObject("artistNames", Map.class);
						genres = (Map<String, Number>) inner.getObject("genres", Map.class);
						genresNames = (Map<String, Number>) inner.getObject("genreNames", Map.class);
					}
					this.cluster = new Cluster(new Track(rs.getString("artist"), rs.getString("track")), cluster,
							rs.getInt("plays"), artists, artistNames, genres, genresNames);
				}
				return this.cluster;
			}
			finally {
				this.connection.setAutoCommit(true);
			}
		}

		List<ClusterDetails> findTopClusters() throws SQLException {
			try (var stmt = newStatement("Find artist's top clusters")) {
				stmt.setString("graphName", this.graphName);
				stmt.setString("artist", this.cluster.seedTrack.artist);
				var clusters = new ArrayList<ClusterDetails>();
				var rs = stmt.executeQuery();
				while (rs.next()) {
					clusters.add(new ClusterDetails(rs.getInt("cluster"), rs.getInt("plays"), rs.getInt("tracks")));
				}
				return List.copyOf(clusters);
			}
		}

		@SuppressWarnings("unchecked")
		RelatedArtists findRelatedArtists() throws SQLException {
			try (var stmt = newStatement("Find related artists of cluster")) {
				stmt.setString("graphName", this.graphName);
				stmt.setObject("artists", this.cluster.artists);
				var rs = stmt.executeQuery();
				if (rs.next()) {
					return new RelatedArtists((Map<String, Number>) rs.getObject("related", Map.class),
							(Map<String, Number>) rs.getObject("relatedNames", Map.class));
				}
				return new RelatedArtists(Map.of(), Map.of());
			}
		}

		Map<String, List<Object>> byCluster(List<ClusterDetails> clusters) throws SQLException {
			System.out.printf(
					"%nMore by %s, from the artist's top clusters, create a playlist with --create-playlist=%s:%n",
					this.cluster.seedTrack.artist, Playlist.BY_CLUSTER.name().toLowerCase(Locale.ROOT));
			try (var stmt = newStatement("Recommend artist's tracks from top clusters")) {
				stmt.setString("graphName", this.graphName);
				stmt.setArray("seedClusters", this.connection.createArrayOf("INTEGER",
						clusters.stream().map(ClusterDetails::cluster).toArray(Integer[]::new)));
				stmt.setString("artist", this.cluster.seedTrack.artist);
				stmt.setString("track", this.cluster.seedTrack.name);

				return printAndTabularizeResultSet(stmt.executeQuery());
			}
		}

		Map<String, List<Object>> byPlayedTogether() throws SQLException {
			System.out.printf("Played with \"%s\" (within 60 minutes), create a playlist with --create-playlist=%s:%n",
					this.cluster.seedTrack.name, Playlist.BY_PLAYED_TOGETHER.name().toLowerCase(Locale.ROOT));
			try (var stmt = newStatement("Recommend tracks played with seed")) {
				stmt.setString("artist", this.cluster.seedTrack.artist);
				stmt.setString("track", this.cluster.seedTrack.name);

				return printAndTabularizeResultSet(stmt.executeQuery());
			}
		}

		Map<String, List<Object>> byClusterAndRelatedArtistsAndGenre(RelatedArtists relatedArtists)
				throws SQLException {
			System.out.printf(
					"Recommendations from the cluster's artists, their related artists and the cluster's genres, create a playlist with --create-playlist=%s:%n",
					Playlist.BY_CLUSTER_AND_RELATED_ARTISTS_AND_GENRE.name().toLowerCase(Locale.ROOT));
			try (var stmt = newStatement("Recommend by cluster artists and genres")) {
				stmt.setString("graphName", this.graphName);
				stmt.setInt("cluster", this.cluster.cluster);
				stmt.setObject("artists", this.cluster.artists);
				stmt.setObject("genres", this.cluster.genres);
				stmt.setObject("related", relatedArtists.related);
				stmt.setString("track", this.cluster.seedTrack.name);

				return printAndTabularizeResultSet(stmt.executeQuery());
			}
		}

	}

	private static final class QueryExtractor extends AbstractVisitor {

		private int currentLevel = -1;

		private String currentQuery;

		final Map<String, String> setup = new LinkedHashMap<>();

		final Map<String, String> recommendations = new LinkedHashMap<>();

		private Map<String, String> queries;

		@Override
		public void visit(FencedCodeBlock fencedCodeBlock) {
			if (this.currentQuery == null) {
				return;
			}
			this.queries.put(this.currentQuery, fencedCodeBlock.getLiteral());

		}

		@Override
		public void visit(Heading heading) {
			this.currentLevel = heading.getLevel();
			super.visit(heading);
			this.currentLevel = -1;
		}

		@Override
		public void visit(Text text) {
			if (this.currentLevel == 3) {
				this.currentQuery = text.getLiteral();
			}
			else if (this.currentLevel == 2) {
				this.currentQuery = null;
				this.queries = switch (text.getLiteral()) {
					case "Setup" -> this.setup;
					case "Recommendations" -> this.recommendations;
					default -> null;
				};
			}
		}

	}

	private record Track(String artist, String name) {
	}

	private record Cluster(Track seedTrack, int cluster, int plays, Map<String, Number> artists,
			Map<String, Number> artistsNames, Map<String, Number> genres, Map<String, Number> genresNames) {
	}

	private record ClusterDetails(int cluster, int plays, int tracks) {
	}

	private record RelatedArtists(Map<String, Number> related, Map<String, Number> relatedNames) {
	}

}
