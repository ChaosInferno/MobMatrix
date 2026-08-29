package org.aincraft.mobmatrix.domain;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class CatalogFixtures {
  private CatalogFixtures() {}

  static CardCatalog currentSnapshot() {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
        CatalogFixtures.class.getResourceAsStream("/catalog/cards.csv"), StandardCharsets.UTF_8))) {
      String header = reader.readLine();
      if (!"id,public_number,name,stars,type,top,right,bottom,left,sale_price".equals(header)) {
        throw new IllegalStateException("unexpected catalog header");
      }
      List<CardDefinition> cards = new ArrayList<>();
      for (String line; (line = reader.readLine()) != null; ) {
        String[] fields = parseCsvLine(line);
        if (fields.length != 10) throw new IllegalStateException("malformed catalog row");
        cards.add(new CardDefinition(new CardId(Integer.parseInt(fields[0])), fields[1], fields[2],
            Integer.parseInt(fields[3]), CardType.valueOf(fields[4].toUpperCase()), Integer.parseInt(fields[5]),
            Integer.parseInt(fields[6]), Integer.parseInt(fields[7]), Integer.parseInt(fields[8]), Integer.parseInt(fields[9])));
      }
      return new CardCatalog(cards, "fixture");
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
  private static String[] parseCsvLine(String line) {
    List<String> fields = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    for (int index = 0; index < line.length(); index++) {
      char character = line.charAt(index);
      if (character == '"') {
        if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
          field.append('"');
          index++;
        } else {
          quoted = !quoted;
        }
      } else if (character == ',' && !quoted) {
        fields.add(field.toString());
        field.setLength(0);
      } else {
        field.append(character);
      }
    }
    if (quoted) throw new IllegalStateException("unterminated CSV quote");
    fields.add(field.toString());
    return fields.toArray(String[]::new);
  }
}
