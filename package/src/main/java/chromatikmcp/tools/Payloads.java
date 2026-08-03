package chromatikmcp.tools;

import java.util.List;
import java.util.Map;

import heronarts.lx.clip.Cursor;
import heronarts.lx.clip.LXClip;
import heronarts.lx.clip.LXClipEvent;
import heronarts.lx.clip.LXClipLane;

import chromatikmcp.domain.ClipEvents;
import chromatikmcp.domain.ClipLanes;
import chromatikmcp.domain.Clips;
import chromatikmcp.domain.Cursors;

/** Payload-shaping helpers shared across tool handlers. */
final class Payloads {

  private Payloads() {}

  /**
   * Puts {@code key} only when {@code value} is non-null. Used for fields backed by
   * {@code Resolve.canonicalPathOrNull} (an object that isn't path-registered has a null
   * path) — omit the key rather than emit a bogus "/null" or a literal JSON null, since a
   * key whose type flips between string and null breaks clients.
   */
  static void putIfPresent(Map<String, Object> map, String key, Object value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  // ── Composition/clip serializers ──────────────────────────────────────────────
  // Thin, discoverable delegates onto the domain builders so every composition tool
  // emits identical cursor / envelope / lane / event shapes by construction.

  /** Read-side cursor object: {@code {millis, beatCount, beatBasis, formatted}}. */
  static Map<String, Object> cursor(LXClip clip, Cursor cursor) {
    return Cursors.toMap(clip, cursor);
  }

  /** Shared clip envelope: identity, timeBase, and every marker as a cursor object. */
  static Map<String, Object> clipEnvelope(LXClip clip) {
    return Clips.envelope(clip);
  }

  /** Per-lane summary: path, index, type, label, eventCount, uiVisible, removable, target. */
  static Map<String, Object> laneSummary(LXClipLane<?> lane) {
    return ClipLanes.summary(lane);
  }

  /** Lane summaries for every lane on the clip, in engine order. */
  static List<Map<String, Object>> laneSummaries(LXClip clip) {
    return ClipLanes.list(clip);
  }

  /** Base event payload {@code {index, cursor}}; extend with type-specific fields. */
  static Map<String, Object> event(LXClipLane<?> lane, LXClipEvent<?> event, int index) {
    return ClipEvents.describe(lane, event, index);
  }
}
