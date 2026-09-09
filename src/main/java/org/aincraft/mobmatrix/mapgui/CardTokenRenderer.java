package org.aincraft.mobmatrix.mapgui;

import de.flog99.mapgui.ui.PaintContext;
import de.flog99.mapgui.ui.Painter;
import de.flog99.mapgui.ui.Rect;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.aincraft.mobmatrix.domain.CardDefinition;
import org.aincraft.mobmatrix.domain.HandCard;
import org.aincraft.mobmatrix.domain.Side;

/** Shared card token art and directional-value rendering for MapGUI screens. */
final class CardTokenRenderer {
  static final Color CARD_SURFACE = new Color(24, 34, 50);
  static final Color CARD_BORDER = new Color(104, 142, 176);
  static final Color MUTED_BORDER = new Color(57, 72, 91);
  static final Color GOLD = new Color(235, 199, 103);
  static final Color CYAN = new Color(119, 220, 242);
  static final Color CARD_VALUE = new Color(255, 239, 151);
  static final Color CARD_VALUE_SHADOW = Color.BLACK;
  static final Color MUTED_TEXT = new Color(148, 164, 182);
  static final BufferedImage CARD_BACK = createCardBack(8, 5);

  private static final int CARD_SIZE = 16;
  private static final Map<ArtKey, BufferedImage> ART_CACHE = new ConcurrentHashMap<>();
  private static final int[][] DIGIT_BITMAPS = {
    {0b111, 0b101, 0b101, 0b101, 0b111},
    {0b010, 0b110, 0b010, 0b010, 0b111},
    {0b110, 0b001, 0b010, 0b100, 0b111},
    {0b110, 0b001, 0b010, 0b001, 0b110},
    {0b101, 0b101, 0b111, 0b001, 0b001},
    {0b111, 0b100, 0b110, 0b001, 0b110},
    {0b011, 0b100, 0b111, 0b101, 0b111},
    {0b111, 0b001, 0b010, 0b010, 0b010},
    {0b111, 0b101, 0b111, 0b101, 0b111},
    {0b111, 0b101, 0b111, 0b001, 0b110}
  };
  private static final int[][] HAND_DIGIT_BITMAPS = {
    {0b0110, 0b1001, 0b1001, 0b1001, 0b0110},
    {0b0010, 0b0110, 0b0010, 0b0010, 0b0111},
    {0b1110, 0b0001, 0b0110, 0b1000, 0b1111},
    {0b1110, 0b0001, 0b0110, 0b0001, 0b1110},
    {0b1001, 0b1001, 0b1111, 0b0001, 0b0001},
    {0b1111, 0b1000, 0b1110, 0b0001, 0b1110},
    {0b0111, 0b1000, 0b1110, 0b1001, 0b0110},
    {0b1111, 0b0001, 0b0010, 0b0100, 0b0100},
    {0b0110, 0b1001, 0b0110, 0b1001, 0b0110},
    {0b0110, 0b1001, 0b0111, 0b0001, 0b1110}
  };

  private CardTokenRenderer() {}

  static void paintPlayerCard(
      PaintContext context, HandCard card, boolean legal, boolean selected) {
    CardDefinition definition = card.definition();
    Painter painter = context.painter();
    Rect bounds = context.bounds();
    Color border =
        selected ? GOLD : context.hovered() && legal ? CYAN : legal ? CARD_BORDER : MUTED_BORDER;
    painter.rect(bounds, CARD_SURFACE, selected ? 2 : 1, border, 2);
    drawArt(painter, bounds, definition.id().value());
    drawDirectionalValues(painter, bounds, definition, legal ? CARD_VALUE : MUTED_TEXT);
  }

  static void drawDirectionalValues(
      Painter painter, Rect bounds, CardDefinition definition, Color color) {
    drawEdgeValue(painter, bounds, definition.top(), true, true, color, true, false);
    drawEdgeValue(painter, bounds, definition.right(), false, false, color, true, false);
    drawEdgeValue(painter, bounds, definition.bottom(), true, false, color, true, false);
    drawEdgeValue(painter, bounds, definition.left(), false, true, color, true, false);
    drawEdgeValue(painter, bounds, definition.top(), true, true, color, false, true);
    drawEdgeValue(painter, bounds, definition.right(), false, false, color, false, true);
    drawEdgeValue(painter, bounds, definition.bottom(), true, false, color, false, true);
    drawEdgeValue(painter, bounds, definition.left(), false, true, color, false, true);
  }

  static void drawEffectiveValues(
      Painter painter, Rect bounds, Map<Side, Integer> effectiveValues) {
    if (effectiveValues.isEmpty()) return;
    Color color = CARD_VALUE;
    drawEdgeValue(painter, bounds, effectiveValues.get(Side.TOP), true, true, color, true, false);
    drawEdgeValue(
        painter, bounds, effectiveValues.get(Side.RIGHT), false, false, color, true, false);
    drawEdgeValue(
        painter, bounds, effectiveValues.get(Side.BOTTOM), true, false, color, true, false);
    drawEdgeValue(painter, bounds, effectiveValues.get(Side.LEFT), false, true, color, true, false);
    drawEdgeValue(painter, bounds, effectiveValues.get(Side.TOP), true, true, color, false, true);
    drawEdgeValue(
        painter, bounds, effectiveValues.get(Side.RIGHT), false, false, color, false, true);
    drawEdgeValue(
        painter, bounds, effectiveValues.get(Side.BOTTOM), true, false, color, false, true);
    drawEdgeValue(painter, bounds, effectiveValues.get(Side.LEFT), false, true, color, false, true);
  }

  static void drawTextCentered(Painter painter, Rect bounds, String text, Color color) {
    painter.textLine(
        bounds.x() + Math.max(0, (bounds.width() - painter.font().widthOf(text)) / 2),
        bounds.y() + Math.max(0, (bounds.height() - painter.font().lineHeight()) / 2),
        text,
        color,
        true);
  }

  static void drawArt(Painter painter, Rect bounds, int cardId) {
    int width = Math.max(1, bounds.width() - 2);
    int height = Math.max(1, bounds.height() - 2);
    drawArt(painter, bounds, cardArt(cardId, width, height));
  }

  static void drawArt(Painter painter, Rect bounds, BufferedImage art) {
    painter.image(
        bounds.x() + (bounds.width() - art.getWidth()) / 2,
        bounds.y() + (bounds.height() - art.getHeight()) / 2,
        art);
  }

  private static void drawEdgeValue(
      Painter painter,
      Rect bounds,
      Integer value,
      boolean horizontal,
      boolean leading,
      Color color,
      boolean drawPlate,
      boolean drawGlyph) {
    if (value == null) return;
    int digits = Integer.toString(Math.max(0, value)).length();
    boolean compact = bounds.width() <= CARD_SIZE;
    boolean wideGlyph = compact && digits == 1;
    boolean twoDigitHand = compact && digits > 1;
    int scaleY = 1;
    int scaleX = 1;
    int width = numberWidth(value, scaleX, wideGlyph, twoDigitHand);
    int height = 5;
    int edgeInset = compact && horizontal ? (twoDigitHand ? 0 : 1) : 2;
    int x;
    int y;
    if (horizontal) {
      x = bounds.x() + Math.max(0, (bounds.width() - width) / 2);
      y = leading ? bounds.y() + edgeInset : bounds.bottom() - height - edgeInset;
    } else {
      x = leading ? bounds.x() + edgeInset : bounds.right() - width - edgeInset;
      y = bounds.y() + Math.max(0, (bounds.height() - height) / 2);
    }
    if (drawPlate)
      drawNumberMask(painter, bounds, x, y, value, scaleX, scaleY, wideGlyph, twoDigitHand);
    if (drawGlyph) drawNumber(painter, x, y, value, color, scaleX, scaleY, wideGlyph, twoDigitHand);
  }

  private static int numberWidth(int value, int scaleX, boolean wideGlyph, boolean twoDigitHand) {
    int digits = Integer.toString(Math.max(0, value)).length();
    int glyphWidth = wideGlyph ? 4 : 3;
    int glyphGap = twoDigitHand ? 2 : 1;
    return digits * (glyphWidth + glyphGap) * scaleX - glyphGap * scaleX;
  }

  private static void drawNumberMask(
      Painter painter,
      Rect bounds,
      int x,
      int y,
      int value,
      int scaleX,
      int scaleY,
      boolean wideGlyph,
      boolean twoDigitHand) {
    String digits = Integer.toString(Math.max(0, value));
    int glyphWidth = wideGlyph ? 4 : 3;
    int advance = (glyphWidth + (twoDigitHand ? 2 : 1)) * scaleX;
    int minX = bounds.x() + 1;
    int minY = bounds.y() + 1;
    int maxX = bounds.right() - 1;
    int maxY = bounds.bottom() - 1;
    for (int digit = 0; digit < digits.length(); digit++) {
      int number = digits.charAt(digit) - '0';
      int[] rows = wideGlyph ? HAND_DIGIT_BITMAPS[number] : DIGIT_BITMAPS[number];
      for (int row = 0; row < rows.length; row++) {
        for (int column = 0; column < glyphWidth; column++) {
          if ((rows[row] & (1 << (glyphWidth - 1 - column))) != 0) {
            int pixelX = x + digit * advance + column * scaleX;
            int pixelY = y + row * scaleY;
            int maskX = Math.max(minX, pixelX - 1);
            int maskY = Math.max(minY, pixelY - 1);
            int maskRight = Math.min(maxX, pixelX + scaleX + 1);
            int maskBottom = Math.min(maxY, pixelY + scaleY + 1);
            painter.fill(
                new Rect(maskX, maskY, maskRight - maskX, maskBottom - maskY), CARD_VALUE_SHADOW);
          }
        }
      }
    }
  }

  private static void drawNumber(
      Painter painter,
      int x,
      int y,
      int value,
      Color color,
      int scaleX,
      int scaleY,
      boolean wideGlyph,
      boolean twoDigitHand) {
    String digits = Integer.toString(Math.max(0, value));
    int glyphWidth = wideGlyph ? 4 : 3;
    int advance = (glyphWidth + (twoDigitHand ? 2 : 1)) * scaleX;
    for (int digit = 0; digit < digits.length(); digit++) {
      int number = digits.charAt(digit) - '0';
      int[] rows = wideGlyph ? HAND_DIGIT_BITMAPS[number] : DIGIT_BITMAPS[number];
      for (int row = 0; row < rows.length; row++) {
        for (int column = 0; column < glyphWidth; column++) {
          if ((rows[row] & (1 << (glyphWidth - 1 - column))) != 0) {
            int pixelX = x + digit * advance + column * scaleX;
            int pixelY = y + row * scaleY;
            painter.fill(new Rect(pixelX, pixelY, scaleX, scaleY), color);
          }
        }
      }
    }
  }

  private static BufferedImage cardArt(int cardId, int width, int height) {
    return ART_CACHE.computeIfAbsent(
        new ArtKey(cardId, width, height),
        key -> createCardArt(key.cardId(), key.width(), key.height()));
  }

  private static BufferedImage createCardArt(int cardId, int width, int height) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      float hue = Math.floorMod(cardId * 47, 360) / 360.0f;
      Color primary = Color.getHSBColor(hue, 0.58f, 0.88f);
      Color shadow = Color.getHSBColor(hue, 0.72f, 0.38f);
      graphics.setPaint(new GradientPaint(0, 0, primary, width, height, shadow));
      graphics.fillRect(0, 0, width, height);
      graphics.setColor(new Color(255, 255, 255, 90));
      graphics.fillOval(1, 0, Math.max(1, width - 2), height);
      graphics.setColor(new Color(12, 18, 30, 220));
      if (Math.floorMod(cardId, 3) == 0) {
        graphics.fillOval(
            Math.max(1, width / 4),
            Math.max(1, height / 8),
            Math.max(1, width / 2),
            Math.max(1, height - 2));
      } else if (Math.floorMod(cardId, 3) == 1) {
        graphics.fillPolygon(
            new int[] {width / 2, Math.max(1, width - 2), 2},
            new int[] {0, Math.max(1, height - 1), Math.max(1, height - 1)},
            3);
      } else {
        graphics.fillRoundRect(2, 1, Math.max(1, width - 4), Math.max(1, height - 2), 4, 4);
      }
      int eyeWidth = Math.max(1, width / 5);
      int eyeHeight = Math.max(1, height / 5);
      int eyeY = Math.max(1, height / 3);
      graphics.setColor(new Color(255, 235, 164));
      graphics.fillRect(Math.max(1, width / 3), eyeY, eyeWidth, eyeHeight);
      graphics.fillRect(Math.max(1, width - width / 3 - eyeWidth), eyeY, eyeWidth, eyeHeight);
      graphics.setColor(new Color(255, 255, 255, 170));
      graphics.drawLine(width / 2, 1, width / 2, Math.max(1, height - 2));
    } finally {
      graphics.dispose();
    }
    return image;
  }

  private static BufferedImage createCardBack(int width, int height) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(new Color(42, 31, 70));
      graphics.fillRect(0, 0, width, height);
      graphics.setColor(new Color(122, 88, 181));
      for (int x = -height; x < width; x += Math.max(2, width / 4)) {
        graphics.drawLine(x, 0, x + height, height);
      }
      graphics.setColor(new Color(222, 187, 255));
      graphics.drawRect(1, 1, Math.max(1, width - 3), Math.max(1, height - 3));
    } finally {
      graphics.dispose();
    }
    return image;
  }

  private record ArtKey(int cardId, int width, int height) {}
}
