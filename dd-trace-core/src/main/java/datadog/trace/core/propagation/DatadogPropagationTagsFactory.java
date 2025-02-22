package datadog.trace.core.propagation;

import static datadog.trace.core.propagation.PropagationTagsFactory.PROPAGATION_ERROR_TAG_KEY;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Captures configuration required for PropagationTags logic */
final class DatadogPropagationTagsFactory {

  private static final Logger log = LoggerFactory.getLogger(DatadogPropagationTagsFactory.class);

  private static final String ALLOWED_TAG_PREFIX = "_dd.p.";
  private static final String DECISION_MAKER_TAG = ALLOWED_TAG_PREFIX + "dm";
  private static final String UPSTREAM_SERVICES_DEPRECATED_TAG =
      ALLOWED_TAG_PREFIX + "upstream_services";

  private static final String PROPAGATION_ERROR_EXTRACT_MAX_SIZE = "extract_max_size";
  private static final String PROPAGATION_ERROR_INJECT_MAX_SIZE = "inject_max_size";
  private static final String PROPAGATION_ERROR_DISABLED = "disabled";
  private static final String PROPAGATION_ERROR_DECODING_ERROR = "decoding_error";

  private static final char TAGS_SEPARATOR = ',';
  private static final char TAG_KEY_SEPARATOR = '=';

  private static final int MIN_ALLOWED_CHAR = 32;
  private static final int MAX_ALLOWED_CHAR = 126;

  private final int datadogTagsLimit;

  DatadogPropagationTagsFactory(int datadogTagsLimit) {
    this.datadogTagsLimit = datadogTagsLimit;
  }

  /**
   * Parses a header value with next eBNF:
   *
   * <pre>
   *   tagset = ( tag, { ",", tag } ) | "";
   *   tag = ( identifier - space or equal ), "=", identifier;
   *   identifier = allowed characters, { allowed characters };
   *   allowed characters = ( ? ASCII characters 32-126 ? - comma );
   *   space or equal = " " | "=";
   *   comma = ",";
   * </pre>
   *
   * All tags prefixed with `_dd.p.` are extracted from tagSet except for `_dd.p.upstream_services`.
   * TagSet that doesn't respect the format will be dropped and a warning will be logged.
   *
   * @return a PropagationTags containing only _dd.p.* tags or an error if the header value is
   *     invalid
   */
  public PropagationTags fromHeaderValue(PropagationTagsFactory tagsFactory, String value) {
    if (value == null) {
      return tagsFactory.empty();
    }
    if (value.length() > datadogTagsLimit) {
      // Incoming x-datadog-tags value length exceeds datadogTagsLimit
      // Set _dd.propagation_error:extract_max_size
      return tagsFactory.createInvalid(PROPAGATION_ERROR_EXTRACT_MAX_SIZE);
    }

    List<String> tagPairs = new ArrayList<>(10);
    int len = value.length();
    int tagPos = 0;
    while (tagPos < len) {
      int tagKeyEndsAt = value.indexOf(TAG_KEY_SEPARATOR, tagPos);
      if (tagKeyEndsAt < 0 || tagKeyEndsAt == len) {
        log.warn(
            "Invalid datadog tags header value: '{}' tag without a value at {}", value, tagPos);
        return tagsFactory.createInvalid(PROPAGATION_ERROR_DECODING_ERROR);
      }
      int tagValuePos = tagKeyEndsAt + 1;
      int tagValueEndsAt = value.indexOf(TAGS_SEPARATOR, tagKeyEndsAt);
      if (tagValueEndsAt < 0) {
        tagValueEndsAt = len;
      }
      String tagKey = value.substring(tagPos, tagKeyEndsAt);
      String tagValue = value.substring(tagValuePos, tagValueEndsAt);
      if (!validateTagKey(tagKey)) {
        log.warn("Invalid datadog tags header value: '{}' invalid tag key at {}", value, tagPos);
        return tagsFactory.createInvalid(PROPAGATION_ERROR_DECODING_ERROR);
      }
      if (tagKey.startsWith(ALLOWED_TAG_PREFIX)
          && !tagKey.startsWith(UPSTREAM_SERVICES_DEPRECATED_TAG)) {
        if (!validateTagValue(tagKey, tagValue)) {
          log.warn(
              "Invalid datadog tags header value: '{}' invalid tag value at {}",
              value,
              tagValuePos);
          return tagsFactory.createInvalid(PROPAGATION_ERROR_DECODING_ERROR);
        }
        tagPairs.add(tagKey);
        tagPairs.add(tagValue);
      }
      tagPos = tagValueEndsAt + 1;
    }
    return tagsFactory.createValid(
        tagPairs, calcTagsLength(tagPairs), containsTag(tagPairs, DECISION_MAKER_TAG));
  }

  String headerValue(PropagationTags propagationTags) {
    int newSize =
        countTagSize(
            propagationTags.tagsSize(),
            DECISION_MAKER_TAG,
            propagationTags.decisionMakerTagValue());

    if (newSize > datadogTagsLimit) {
      // Drop all the tags when the outgoing value length exceeds the configured limit
      return null;
    }
    // No encoding validation here because we don't allow arbitrary tag change

    Iterator<String> it = propagationTags.tagPairs().iterator();
    StringBuilder sb = new StringBuilder();
    while (it.hasNext()) {
      String tagKey = it.next();
      String tagValue = it.next();
      appendTag(sb, tagKey, tagValue);
    }
    if (propagationTags.missingDecisionMaker() && propagationTags.decisionMakerTagValue() != null) {
      appendTag(sb, DECISION_MAKER_TAG, propagationTags.decisionMakerTagValue());
    }
    return sb.length() > 0 ? sb.toString() : null;
  }

  public void fillTagMap(PropagationTags propagationTags, Map<String, String> tagMap) {
    int newSize =
        countTagSize(
            propagationTags.tagsSize(),
            DECISION_MAKER_TAG,
            propagationTags.decisionMakerTagValue());

    if (newSize > datadogTagsLimit) {
      // Outgoing x-datadog-tags value length exceeds the configured limit
      // Set _dd.propagation_error:inject_max_size if the configured limit is greater than zero,
      // else set _dd.propagation_error:disabled
      if (datadogTagsLimit == 0) {
        tagMap.put(PROPAGATION_ERROR_TAG_KEY, PROPAGATION_ERROR_DISABLED);
      } else {
        tagMap.put(PROPAGATION_ERROR_TAG_KEY, PROPAGATION_ERROR_INJECT_MAX_SIZE);
      }
      return;
    }

    Iterator<String> it = propagationTags.tagPairs().iterator();
    while (it.hasNext()) {
      String tagKey = it.next();
      String tagValue = it.next();
      tagMap.put(tagKey, tagValue);
    }
    if (propagationTags.missingDecisionMaker() && propagationTags.decisionMakerTagValue() != null) {
      tagMap.put(DECISION_MAKER_TAG, propagationTags.decisionMakerTagValue());
    }
  }

  private static boolean containsTag(List<String> tagPairs, String tagName) {
    for (int i = 0; i < tagPairs.size(); i += 2) {
      if (tagPairs.get(i).equals(tagName)) {
        return true;
      }
    }
    return false;
  }

  private static int calcTagsLength(List<String> tagPairs) {
    int size = 0;
    for (String tagPair : tagPairs) {
      size += tagPair.length();
      size += 1; // tag or key separator
    }
    return size - 1; // exclude last separator
  }

  private static void appendTag(StringBuilder sb, String tagKey, String tagValue) {
    if (sb.length() > 0) {
      sb.append(TAGS_SEPARATOR);
    }
    sb.append(tagKey);
    sb.append(TAG_KEY_SEPARATOR);
    sb.append(tagValue);
  }

  private static int countTagSize(int size, String tagKey, String tagValue) {
    if (tagValue != null) {
      if (size > 0) {
        // tag separator
        size += 1;
      }
      size += tagKey.length();
      // tag key separator
      size += 1;
      size += tagValue.length();
    }
    return size;
  }

  private static boolean validateTagKey(String tagKey) {
    for (int i = 0; i < tagKey.length(); i++) {
      char c = tagKey.charAt(i);
      if (!isAllowedKeyChar(c)) {
        return false;
      }
    }
    return true;
  }

  private static boolean validateTagValue(String tagKey, String tagValue) {
    for (int i = 0; i < tagValue.length(); i++) {
      char c = tagValue.charAt(i);
      if (!isAllowedValueChar(c)) {
        return false;
      }
    }
    if (tagKey.equals(DECISION_MAKER_TAG) && !validateDecisionMakerTag(tagValue)) {
      return false;
    }
    return true;
  }

  private static boolean isAllowedKeyChar(char c) {
    // space (MIN_ALLOWED_CHAR) is not allowed
    return c > MIN_ALLOWED_CHAR && c <= MAX_ALLOWED_CHAR && c != TAGS_SEPARATOR;
  }

  private static boolean isAllowedValueChar(char c) {
    return c >= MIN_ALLOWED_CHAR && c <= MAX_ALLOWED_CHAR && c != TAG_KEY_SEPARATOR;
  }

  /**
   * Validates the _dd.p.dm tag format with next eBNF:
   *
   * <pre>
   *   _dd.p.dm = [ service hash ], "-", sampling mechanism;
   *
   *   digit = "0" | "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" ;
   *   hexadecimal digit = digit | "a" | "b" | "c" | "d" | "e" | "f" ;
   *
   *   service hash = 10 * hexadecimal digit;
   *   sampling mechanism = digit, { digit };
   * </pre>
   */
  private static boolean validateDecisionMakerTag(String value) {
    int sepPos = value.indexOf('-');
    if (sepPos < 0) {
      // missing separator
      return false;
    }
    if (sepPos != 0 && sepPos != 10) {
      // invalid service hash length
      return false;
    }
    int samplingMechanismPos = sepPos + 1;
    int len = value.length();
    if (samplingMechanismPos == len) {
      // missing sampling mechanism
      return false;
    }
    for (int i = 0; i < sepPos; i++) {
      if (!isHexDigit(value.charAt(i))) {
        // invalid service hash char
        return false;
      }
    }
    for (int i = samplingMechanismPos; i < len; i++) {
      if (!isDigit(value.charAt(i))) {
        // invalid sampling mechanism
        return false;
      }
    }
    return true;
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isHexDigit(char c) {
    return c >= 'a' && c <= 'f' || isDigit(c);
  }
}
