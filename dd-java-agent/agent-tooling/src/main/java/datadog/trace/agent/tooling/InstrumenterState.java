package datadog.trace.agent.tooling;

import datadog.trace.bootstrap.WeakCache;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLongArray;

/** Tracks {@link Instrumenter} state, such as where it was applied and where it was blocked. */
public final class InstrumenterState {

  public interface Observer {
    void applied(Iterable<String> instrumentationNames);
  }

  // constants for representing a bitset as a series of words
  private static final int ADDRESS_BITS_PER_WORD = 6;
  private static final int BITS_PER_WORD = 1 << ADDRESS_BITS_PER_WORD;
  private static final int BIT_INDEX_MASK = BITS_PER_WORD - 1;

  // represent each status as 2 adjacent bits
  private static final int BLOCKED = 0b01;
  private static final int APPLIED = 0b10;

  private static final int STATUS_BITS = 0b11;

  private static Iterable<String>[] instrumentationNames = new Iterable[0];

  private static long[] defaultState = {};

  /** Tracks which instrumentations were applied (per-class-loader) and which were blocked. */
  private static final WeakCache<ClassLoader, AtomicLongArray> classLoaderStates =
      WeakCaches.newWeakCache(64);

  private static Observer observer;

  private InstrumenterState() {}

  /** Pre-sizes internal structures to accommodate the highest expected id. */
  public static void setMaxInstrumentationId(int maxInstrumentationId) {
    instrumentationNames = Arrays.copyOf(instrumentationNames, maxInstrumentationId + 1);
  }

  /** Registers an instrumentation's primary name plus zero or more aliases. */
  public static void registerInstrumentationNames(int instrumentationId, Iterable<String> names) {
    if (instrumentationId >= instrumentationNames.length) {
      // note: setMaxInstrumentationId pre-sizes array to avoid repeated allocations here
      instrumentationNames = Arrays.copyOf(instrumentationNames, instrumentationId + 16);
    }
    instrumentationNames[instrumentationId] = names;
  }

  /** Registers an observer to be notified whenever an instrumentation is applied. */
  public static void setObserver(Observer observer) {
    InstrumenterState.observer = observer;
  }

  /** Resets the default instrumentation state so nothing is blocked or applied. */
  public static void resetDefaultState() {
    int instrumentationCount = instrumentationNames.length;

    int wordsPerClassLoaderState =
        ((instrumentationCount << 1) + BITS_PER_WORD - 1) >> ADDRESS_BITS_PER_WORD;

    if (defaultState.length > 0) { // optimization: skip clear if there's no old state
      classLoaderStates.clear();
    }

    defaultState = new long[wordsPerClassLoaderState];
  }

  /**
   * Does the instrumentation apply to the given class-loader?
   *
   * @return {@code null} if checks such as muzzle are still pending
   */
  public static Boolean isApplicable(ClassLoader classLoader, int instrumentationId) {
    int status = currentState(classLoader, instrumentationId);
    return status == 0 ? null : status == APPLIED;
  }

  /** Records that the instrumentation was applied to the given class-loader. */
  public static void applyInstrumentation(ClassLoader classLoader, int instrumentationId) {
    updateState(classLoader, instrumentationId, APPLIED);
    if (null != observer) {
      observer.applied(instrumentationNames[instrumentationId]);
    }
  }

  /** Records that the instrumentation is blocked for the given class-loader. */
  public static void blockInstrumentation(ClassLoader classLoader, int instrumentationId) {
    updateState(classLoader, instrumentationId, BLOCKED);
  }

  /** Records that the instrumentation is blocked by default. */
  public static void blockInstrumentation(int instrumentationId) {
    int bitIndex = instrumentationId << 1;
    int wordIndex = bitIndex >> ADDRESS_BITS_PER_WORD;
    long bitsToSet = ((long) BLOCKED) << (bitIndex & BIT_INDEX_MASK);
    synchronized (defaultState) {
      defaultState[wordIndex] |= bitsToSet;
    }
  }

  private static int currentState(ClassLoader classLoader, int instrumentationId) {
    return currentState(classLoaderState(classLoader), instrumentationId << 1);
  }

  private static void updateState(ClassLoader classLoader, int instrumentationId, int status) {
    updateState(classLoaderState(classLoader), instrumentationId << 1, status);
  }

  private static AtomicLongArray classLoaderState(ClassLoader classLoader) {
    return classLoaderStates.computeIfAbsent(
        null != classLoader ? classLoader : Utils.getBootstrapProxy(),
        input -> new AtomicLongArray(defaultState));
  }

  private static int currentState(AtomicLongArray state, int bitIndex) {
    int wordIndex = bitIndex >> ADDRESS_BITS_PER_WORD;
    return STATUS_BITS & (int) (state.get(wordIndex) >> (bitIndex & BIT_INDEX_MASK));
  }

  private static void updateState(AtomicLongArray state, int bitIndex, int status) {
    int wordIndex = bitIndex >> ADDRESS_BITS_PER_WORD;
    long bitsToSet = ((long) status) << (bitIndex & BIT_INDEX_MASK);
    long wordState = state.get(wordIndex);
    while (!state.compareAndSet(wordIndex, wordState, wordState | bitsToSet)) {
      wordState = state.get(wordIndex);
    }
  }
}
