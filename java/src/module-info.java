module recommendations {
	requires org.commonmark;
	requires org.neo4j.jdbc;
	requires info.picocli;
	requires tagtraum.obstmusic;

	opens recommendations to info.picocli;
}