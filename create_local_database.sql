CREATE OR REPLACE VIEW my_plays AS (
  SELECT * FROM src.plays WHERE user_id = 1
);


CREATE OR REPLACE VIEW my_tracks AS (
  SELECT * FROM src.tracks t
  WHERE year IS NOT NULL
    AND EXISTS (SELECT '' FROM my_plays hlp WHERE hlp.track_id = t.id)
);


CREATE TABLE decades AS (
    SELECT row_number() OVER (ORDER BY value) AS id,
        value
    FROM (
             SELECT DISTINCT year-year%10 AS value
             FROM my_tracks
             WHERE year IS NOT NULL ORDER BY value
         )
);
ALTER TABLE decades ADD PRIMARY KEY (id);


CREATE TABLE years AS (
    SELECT row_number() OVER (ORDER BY src.value) AS ID,
        src.value,
           d.id AS decade_id
    FROM (
             SELECT DISTINCT year AS value
             FROM my_tracks
             WHERE year IS NOT NULL
             ORDER BY value
         ) src JOIN decades d ON d.value = src.value - src.value%10
);
ALTER TABLE years ADD PRIMARY KEY (id);


CREATE TABLE albums AS (
    SELECT row_number() OVER (ORDER BY album, artist_id) AS id,
        *
    FROM (
             SELECT DISTINCT album, t.artist_id, genre_id, t.year, discogs_id
             FROM my_tracks t
                      JOIN src.artists a ON a.id = t.artist_id
                      LEFT OUTER JOIN src.albums a ON a.artist_id = t.artist_id AND a.name = t.album AND a.year = t.year
             WHERE NOT t.compilation
         )
);


ALTER TABLE albums ADD PRIMARY KEY (id);
SELECT count(*) FROM albums;


CREATE TABLE artists AS SELECT * FROM src.artists a WHERE EXISTS (SELECT '' FROM my_tracks t where t.artist_id = a.id);
ALTER TABLE artists ADD PRIMARY KEY (id);


CREATE TABLE genres AS SELECT * FROM src.genres g WHERE EXISTS (SELECT '' from my_tracks t where g.id = t.genre_id);
ALTER TABLE genres ADD PRIMARY KEY (id);


CREATE TABLE tracks AS (
    SELECT t.id, a.id as album_id, name, played_count, rating, duration, disc_number, disc_count, track_number, track_count
    FROM my_tracks t
             JOIN albums a ON a.artist_id = t.artist_id
        AND a.genre_id = t.genre_id
        AND a.album = t.album
        AND a.year = t.year
);
ALTER TABLE tracks ADD PRIMARY KEY (id);
SELECT count(*) FROM tracks;


CREATE TABLE plays AS (
    SELECT p.*
    FROM my_plays p JOIN tracks t ON t.id = p.track_id
);
ALTER TABLE plays ADD PRIMARY KEY (id);
SELECT count(*) FROM plays;


CREATE VIEW days AS (
  SELECT row_number() OVER (ORDER BY day) AS id, day
  FROM (
    SELECT DISTINCT date(played_on) AS day FROM plays
  )
);

CREATE VIEW played_on AS (
  SELECT plays.id, days.id as day_id, played_on, track_id
  FROM plays JOIN days ON day = date(played_on)
);
