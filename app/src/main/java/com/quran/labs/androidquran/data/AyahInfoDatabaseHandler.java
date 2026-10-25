package com.quran.labs.androidquran.data;

import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.RectF;

import androidx.annotation.NonNull;

import com.quran.labs.androidquran.database.DatabaseUtils;
import com.quran.labs.androidquran.util.QuranFileUtils;
import com.quran.page.common.data.AyahBounds;
import com.quran.page.common.data.AyahCoordinates;
import com.quran.page.common.data.AyahMarkerLocation;
import com.quran.page.common.data.PageCoordinates;
import com.quran.page.common.data.SuraHeaderLocation;
import com.quran.page.common.data.coordinates.PageGlyphsCoords;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AyahInfoDatabaseHandler {
  private static final RectF EMPTY_BOUNDS = new RectF();
  private static final String COL_PAGE = "page_number";
  private static final String COL_LINE = "line_number";
  private static final String COL_SURA = "sura_number";
  private static final String COL_AYAH = "ayah_number";
  private static final String COL_POSITION = "position";
  private static final String MIN_X = "min_x";
  private static final String MIN_Y = "min_y";
  private static final String MAX_X = "max_x";
  private static final String MAX_Y = "max_y";
  private static final String GLYPH_TYPE = "glyph_type";
  private static final String GLYPHS_TABLE = "glyphs";

  private static final Map<String, AyahInfoDatabaseHandler> ayahInfoCache = new HashMap<>();
  private static File quranDatabaseDirectory = null;

  // Glyphs narrower than this share of the text width are pause marks, not words.
  private static final float MAX_MARK_WIDTH_RATIO = 0.019f;
  // Room around a pause mark's glyph, as a share of the text width.
  private static final float MARK_PADDING_RATIO = 0.004f;
  // Room beyond the text's edges for the words starting and ending lines (strokes and vowel marks
  // reaching past their glyphs), as a share of the text width; within the page's margins.
  private static final float EDGE_PADDING_RATIO = 0.01f;

  private final SQLiteDatabase database;
  private final boolean hasGlyphData;
  private int textWidth = -1;

  static AyahInfoDatabaseHandler getAyahInfoDatabaseHandler(
      String databaseName, QuranFileUtils quranFileUtils) {
    final File currentAyahDatabaseDirectory = quranFileUtils.getQuranAyahDatabaseDirectory();
    if (!currentAyahDatabaseDirectory.equals(quranDatabaseDirectory)) {
      quranDatabaseDirectory = currentAyahDatabaseDirectory;
      clearAyahInfoCache();
    }

    AyahInfoDatabaseHandler handler = ayahInfoCache.get(databaseName);
    if (handler == null) {
      try {
        AyahInfoDatabaseHandler db = new AyahInfoDatabaseHandler(databaseName, quranFileUtils);
        if (db.validDatabase()) {
          ayahInfoCache.put(databaseName, db);
          handler = db;
        }
      } catch (SQLException sqlException) {
        // it's okay, we'll try again later
      }
    }
    return handler;
  }

  private static void clearAyahInfoCache() {
    final Set<String> keys = ayahInfoCache.keySet();
    for (String key : keys) {
      final AyahInfoDatabaseHandler handler = ayahInfoCache.get(key);
      try {
        if (handler != null) {
          handler.database.close();
        }
      } catch (Exception e) {
        // ignore
      }
    }
    ayahInfoCache.clear();
  }

  private AyahInfoDatabaseHandler(String databaseName,
                                  QuranFileUtils quranFileUtils) throws SQLException {
    File base = quranFileUtils.getQuranAyahDatabaseDirectory();
    File path = new File(base, databaseName);
    database = SQLiteDatabase.openDatabase(path.getAbsolutePath(), null, SQLiteDatabase.NO_LOCALIZED_COLLATORS);
    hasGlyphData = quranFileUtils.getAyahInfoDbHasGlyphData();
  }

  private boolean validDatabase() {
    return database != null && database.isOpen();
  }

  @NonNull
  public PageCoordinates getPageInfo(int page, boolean wantBounds) {
    final RectF bounds = wantBounds ? getPageBounds(page) : EMPTY_BOUNDS;
    if (haveVerseMarkerData()) {
      return new PageCoordinates(page,
          bounds,
          getSuraHeadersForPage(page),
          getVerseMarkersForPage(page));
    } else {
      return new PageCoordinates(page, bounds, new ArrayList<>(), new ArrayList<>());
    }
  }

  @NonNull
  private RectF getPageBounds(int page) {
    Cursor c = null;
    try {
      String[] colNames = new String[]{
          "MIN(" + MIN_X + ")", "MIN(" + MIN_Y + ")",
          "MAX(" + MAX_X + ")", "MAX(" + MAX_Y + ")" };
      c = database.query(GLYPHS_TABLE, colNames, COL_PAGE + "=" + page, null, null, null, null);
      if (c.moveToFirst()) {
        return new RectF(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3));
      } else {
        throw new IllegalArgumentException("getPageBounds() on a non-existent page: " + page);
      }
    } finally {
      DatabaseUtils.closeCursor(c);
    }
  }

  @NonNull
  public AyahCoordinates getVersesBoundsForPage(int page) {
    boolean includeGlyphData = hasGlyphData && haveGlyphData();
    GlyphsBuilder glyphsBuilder = new GlyphsBuilder();
    Map<String, List<AyahBounds>> ayahBounds = new HashMap<>();
    Cursor cursor = null;
    try {
      cursor = getVersesBoundsCursorForPage(page, includeGlyphData);
      while (cursor.moveToNext()) {
        int line = cursor.getInt(1);
        int sura = cursor.getInt(2);
        int ayah = cursor.getInt(3);
        int position = cursor.getInt(4);
        int minX = cursor.getInt(5);
        int minY = cursor.getInt(6);
        int maxX = cursor.getInt(7);
        int maxY = cursor.getInt(8);

        if (includeGlyphData) {
          String glyphType = cursor.getString(9);
          RectF r = new RectF(minX, minY, maxX, maxY);
          glyphsBuilder.append(sura, ayah, position, line, glyphType, r);
        }

        String key = sura + ":" + ayah;
        List<AyahBounds> bounds = ayahBounds.get(key);
        if (bounds == null) {
          bounds = new ArrayList<>();
        }

        AyahBounds last = null;
        if (bounds.size() > 0) {
          last = bounds.get(bounds.size() - 1);
        }

        AyahBounds bound = new AyahBounds(line, position, minX, minY, maxX, maxY);
        if (last != null && last.getLine() == bound.getLine()) {
          last.engulf(bound);
        } else {
          bounds.add(bound);
        }
        ayahBounds.put(key, bounds);
      }
    } finally {
      DatabaseUtils.closeCursor(cursor);
    }

    PageGlyphsCoords glyphCoords = includeGlyphData ?
        new PageGlyphsCoords(page, glyphsBuilder.build()) : null;
    Map<String, List<AyahBounds>> wordBounds = includeGlyphData ?
        Collections.emptyMap() : getWordBoundsForPage(page);

    return new AyahCoordinates(page, ayahBounds, glyphCoords, Collections.emptyMap(), 0f, wordBounds);
  }

  /**
   * Bounds of each word on the page ("sura:ayah:word", words counted from 1 within the ayah), for
   * databases without glyph types. Their glyphs are, in order, an ayah's words and pause marks and
   * then its number. Pause marks are told apart by their width; this matches the word count of the
   * Arabic text in all but 2 of the 6236 ayahs of the Madani pages, at every page size. Pause marks
   * are part of the word before them, and each word's box spans its line's height.
   */
  @NonNull
  private Map<String, List<AyahBounds>> getWordBoundsForPage(int page) {
    final Map<String, List<AyahBounds>> words = new HashMap<>();
    Cursor cursor = null;
    try {
      final float maxMarkWidth = MAX_MARK_WIDTH_RATIO * getTextWidth();
      final float markPadding = MARK_PADDING_RATIO * getTextWidth();
      final float edgePadding = EDGE_PADDING_RATIO * getTextWidth();
      // every glyph of the ayahs on this page, since words are counted from the start of the ayah
      cursor = database.rawQuery("SELECT g." + COL_PAGE + ", g." + COL_LINE + ", g." + COL_SURA +
          ", g." + COL_AYAH + ", g." + COL_POSITION + ", g." + MIN_X + ", g." + MIN_Y +
          ", g." + MAX_X + ", g." + MAX_Y + " FROM " + GLYPHS_TABLE + " g JOIN (SELECT DISTINCT " +
          COL_SURA + ", " + COL_AYAH + " FROM " + GLYPHS_TABLE + " WHERE " + COL_PAGE + " = ?) a ON g." +
          COL_SURA + " = a." + COL_SURA + " AND g." + COL_AYAH + " = a." + COL_AYAH +
          " ORDER BY g." + COL_SURA + ", g." + COL_AYAH + ", g." + COL_POSITION,
          new String[] { String.valueOf(page) });
      final List<int[]> rows = new ArrayList<>();
      while (cursor.moveToNext()) {
        final int[] row = new int[9];
        for (int i = 0; i < row.length; i++) {
          row[i] = cursor.getInt(i);
        }
        rows.add(row);
      }

      // Each line's height, so a word's box covers its marks and dots above and below, and where
      // it ends on the left; and the extent of the page's text.
      final Map<Integer, int[]> lines = new HashMap<>();
      int textLeft = Integer.MAX_VALUE;
      int textRight = Integer.MIN_VALUE;
      for (int[] row : rows) {
        if (row[0] != page) {
          continue;
        }
        final int[] line = lines.get(row[1]);
        if (line == null) {
          lines.put(row[1], new int[] { row[6], row[8], row[5] });
        } else {
          line[0] = Math.min(line[0], row[6]);
          line[1] = Math.max(line[1], row[8]);
          line[2] = Math.min(line[2], row[5]);
        }
        textLeft = Math.min(textLeft, row[5]);
        textRight = Math.max(textRight, row[7]);
      }

      // A word's box runs from its right edge to the next word on its line (the text is right to
      // left), so the pause marks in between belong to the word before them and are hidden and
      // colored with it.
      final List<WordBox> boxes = new ArrayList<>();
      WordBox last = null;
      int word = 0;
      for (int i = 0; i < rows.size(); i++) {
        final int[] row = rows.get(i);
        if (i == 0 || !sameAyah(rows.get(i - 1), row)) {
          word = 0;
          last = null;
        }
        final boolean ayahNumber = i == rows.size() - 1 || !sameAyah(row, rows.get(i + 1));
        final boolean pauseMark = !ayahNumber && row[7] - row[5] <= maxMarkWidth;
        if (!ayahNumber && !pauseMark) {
          word++;
        }
        if (row[0] != page || ayahNumber || word == 0) {
          last = null;
          continue;
        }
        if (pauseMark) {
          // its glyph is only an anchor, often a few pixels wide
          final float center = (row[5] + row[7]) / 2f;
          final float half = Math.max(Math.abs(row[7] - row[5]), row[8] - row[6]) / 2f + markPadding;
          if (last != null && last.line == row[1]) {
            last.left = Math.min(last.left, center - half);
          } else {
            last = new WordBox(row[2] + ":" + row[3] + ":" + word, row[1], row[4], center - half, center + half);
            boxes.add(last);
          }
        } else {
          if (last != null && last.line == row[1]) {
            last.left = Math.min(last.left, row[7]);
          }
          last = new WordBox(row[2] + ":" + row[3] + ":" + word, row[1], row[4], row[5], row[7]);
          boxes.add(last);
        }
      }

      // Nothing comes before the first word of a line (the text is right to left) to reach it, so
      // its box runs to the right edge of the text, past what of its letters sticks out of its
      // glyph (strokes, vowel marks); likewise a word ending its line runs to the left edge.
      final Map<Integer, WordBox> lineStarts = new HashMap<>();
      final Map<Integer, WordBox> lineEnds = new HashMap<>();
      for (WordBox box : boxes) {
        final WordBox start = lineStarts.get(box.line);
        if (start == null || box.right > start.right) {
          lineStarts.put(box.line, box);
        }
        final WordBox end = lineEnds.get(box.line);
        if (end == null || box.left < end.left) {
          lineEnds.put(box.line, box);
        }
      }
      for (WordBox box : lineStarts.values()) {
        box.right = Math.max(box.right, textRight + edgePadding);
      }
      for (WordBox box : lineEnds.values()) {
        // only when no ayah number follows it on the line
        if (box.left <= lines.get(box.line)[2]) {
          box.left = Math.min(box.left, textLeft - edgePadding);
        }
      }

      for (WordBox box : boxes) {
        final int[] line = lines.get(box.line);
        List<AyahBounds> bounds = words.get(box.key);
        if (bounds == null) {
          bounds = new ArrayList<>();
          words.put(box.key, bounds);
        }
        bounds.add(new AyahBounds(box.line, box.position,
            Math.round(box.left), line[0], Math.round(box.right), line[1]));
      }
    } catch (Exception e) {
      // word highlights are optional; ayah highlights still work
      words.clear();
    } finally {
      DatabaseUtils.closeCursor(cursor);
    }
    return words;
  }

  private static class WordBox {
    final String key;
    final int line;
    final int position;
    float left;
    float right;

    WordBox(String key, int line, int position, float left, float right) {
      this.key = key;
      this.line = line;
      this.position = position;
      this.left = left;
      this.right = right;
    }
  }

  private static boolean sameAyah(int[] first, int[] second) {
    return first[2] == second[2] && first[3] == second[3];
  }

  private int getTextWidth() {
    if (textWidth < 0) {
      Cursor cursor = null;
      try {
        cursor = database.rawQuery(
            "SELECT MAX(" + MAX_X + ") - MIN(" + MIN_X + ") FROM " + GLYPHS_TABLE, null);
        textWidth = cursor.moveToFirst() ? cursor.getInt(0) : 0;
      } finally {
        DatabaseUtils.closeCursor(cursor);
      }
    }
    return textWidth;
  }

  private boolean haveVerseMarkerData() {
    Cursor cursor = null;
    try {
      cursor = database.rawQuery("SELECT count(1) from sqlite_master WHERE name = ?",
          new String[] { "ayah_markers" });
      return cursor.moveToFirst() && cursor.getInt(0) > 0;
    } finally {
      DatabaseUtils.closeCursor(cursor);
    }
  }

  private boolean haveGlyphData() {
    Cursor cursor = null;
    try {
      cursor = database.query(GLYPHS_TABLE,
          new String[]{GLYPH_TYPE}, null, null, null, null, null, "1");
      return cursor.moveToFirst() && cursor.getString(0) != null;
    } catch (Exception e) {
      // we don't have glyph data (the column doesn't exist)
      return false;
    } finally {
      DatabaseUtils.closeCursor(cursor);
    }
  }

  private List<AyahMarkerLocation> getVerseMarkersForPage(int page) {
    final List<AyahMarkerLocation> markers = new ArrayList<>();
    Cursor cursor = null;
    try {
      cursor = database.query("ayah_markers",
          new String[] { "sura_number", "ayah_number", "x", "y" },
          "page_number = ?", new String[] { String.valueOf(page) }, null,
          null, "sura_number, ayah_number ASC");
      while (cursor.moveToNext()) {
        final int sura = cursor.getInt(0);
        final int ayah = cursor.getInt(1);
        final int x = cursor.getInt(2);
        final int y = cursor.getInt(3);
        markers.add(new AyahMarkerLocation(sura, ayah, x, y));
      }
    } finally {
      DatabaseUtils.closeCursor(cursor);
    }
    return markers;
  }

  private List<SuraHeaderLocation> getSuraHeadersForPage(int page) {
    final List<SuraHeaderLocation> headers = new ArrayList<>();
    Cursor cursor = null;
    try {
      cursor = database.query("sura_headers",
          new String[] { "sura_number", "x", "y", "width", "height" },
          "page_number = ?", new String[] { String.valueOf(page) }, null,
          null, "sura_number ASC");
      while (cursor.moveToNext()) {
        final int sura = cursor.getInt(0);
        final int x = cursor.getInt(1);
        final int y = cursor.getInt(2);
        final int width = cursor.getInt(3);
        final int height = cursor.getInt(4);
        headers.add(new SuraHeaderLocation(sura, x, y, width, height));
      }
    } finally {
      DatabaseUtils.closeCursor(cursor);
    }
    return headers;
  }

  private Cursor getVersesBoundsCursorForPage(int page, boolean withGlyphData) {
    String[] columns = withGlyphData ?
        new String[]{COL_PAGE, COL_LINE, COL_SURA, COL_AYAH, COL_POSITION, MIN_X, MIN_Y, MAX_X, MAX_Y, GLYPH_TYPE} :
        new String[]{COL_PAGE, COL_LINE, COL_SURA, COL_AYAH, COL_POSITION, MIN_X, MIN_Y, MAX_X, MAX_Y};

    return database.query(GLYPHS_TABLE,
        columns,
        COL_PAGE + "=" + page,
        null, null, null,
        COL_SURA + "," + COL_AYAH + "," + COL_POSITION);
  }
}
