package io.github.shellci.shell;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/** Parses ShellCheck output in either the {@code json1} ({"comments": [...]}) or legacy {@code json} ([...]) format. */
public final class ShellCheckReportParser {

  private static final Gson GSON = new Gson();

  private ShellCheckReportParser() {
  }

  public static List<ShellCheckComment> parse(Reader reader) {
    JsonElement root;
    try {
      root = GSON.fromJson(reader, JsonElement.class);
    } catch (JsonParseException e) {
      throw new IllegalArgumentException("Invalid ShellCheck JSON report: " + e.getMessage(), e);
    }
    if (root == null || root.isJsonNull()) {
      return List.of();
    }
    JsonArray comments;
    if (root.isJsonArray()) {
      comments = root.getAsJsonArray();
    } else if (root.isJsonObject() && root.getAsJsonObject().has("comments")) {
      comments = root.getAsJsonObject().getAsJsonArray("comments");
    } else {
      throw new IllegalArgumentException("Unrecognized ShellCheck report: expected the json1 or json format");
    }
    List<ShellCheckComment> result = new ArrayList<>(comments.size());
    for (JsonElement element : comments) {
      JsonObject c = element.getAsJsonObject();
      int line = intOr(c, "line", 1);
      int column = intOr(c, "column", 1);
      result.add(new ShellCheckComment(
        c.has("file") ? c.get("file").getAsString() : "",
        line,
        intOr(c, "endLine", line),
        column,
        intOr(c, "endColumn", column),
        c.has("level") ? c.get("level").getAsString() : "warning",
        intOr(c, "code", 0),
        c.has("message") ? c.get("message").getAsString() : ""));
    }
    return result;
  }

  private static int intOr(JsonObject object, String member, int fallback) {
    JsonElement value = object.get(member);
    return value == null || value.isJsonNull() ? fallback : value.getAsInt();
  }
}
