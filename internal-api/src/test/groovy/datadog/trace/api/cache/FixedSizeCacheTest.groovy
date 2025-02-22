package datadog.trace.api.cache


import datadog.trace.test.util.DDSpecification
import spock.lang.Shared
import spock.util.concurrent.AsyncConditions

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function

class FixedSizeCacheTest extends DDSpecification {
  def "invalid capacities are rejected"() {
    when:
    DDCaches.newFixedSizeCache(capacity)

    then:
    thrown(IllegalArgumentException)

    where:
    capacity << [Integer.MIN_VALUE, -1, 0]
  }

  def "cache can be explicitly cleared"() {
    setup:
    def fsCache = DDCaches.newFixedSizeCache(15)

    when:
    fsCache.computeIfAbsent("test-key", { "first-value" })

    then:
    fsCache.computeIfAbsent("test-key", { "second-value" }) == "first-value"

    when:
    fsCache.clear()

    then:
    fsCache.computeIfAbsent("test-key", { "second-value" }) == "second-value"
  }

  def "fixed size should store and retrieve values"() {
    setup:
    def fsCache = DDCaches.newFixedSizeCache(15)
    def creationCount = new AtomicInteger(0)
    def tvc = new TVC(creationCount)
    def tk1 = new TKey(1, 1, "one")
    def tk6 = new TKey(6, 6, "six")
    def tk10 = new TKey(10, 10, "ten")
    // insert some values that happen to be the chain of hashes 1 -> 6 -> 10
    fsCache.computeIfAbsent(tk1, tvc)
    fsCache.computeIfAbsent(tk6, tvc)
    fsCache.computeIfAbsent(tk10, tvc)

    expect:
    fsCache.computeIfAbsent(tk, tvc) == value
    creationCount.get() == count

    where:
    tk                        | value          | count
    new TKey(1, 1, "foo")     | "one_value"    | 3     // used the cached tk1
    new TKey(1, 6, "foo")     | "six_value"    | 3     // used the cached tk6
    new TKey(1, 10, "foo")    | "ten_value"    | 3     // used the cached tk10
    new TKey(6, 6, "foo")     | "six_value"    | 3     // used the cached tk6
    new TKey(1, 11, "eleven") | "eleven_value" | 4     // create new value in an occupied slot
    new TKey(4, 4, "four")    | "four_value"   | 4     // create new value in empty slot
    null                      | null           | 3     // do nothing
  }

  def "fixed size with array-keys should store and retrieve values"() {
    setup:
    def fsCache = DDCaches.newFixedSizeArrayKeyCache(15)
    def creationCount = new AtomicInteger(0)
    def tvc = new TVCA(creationCount)
    // need to offset key hashes by 31 to achieve the same collisions using array hashing
    def tk1 = [new TKey(1 - 31, 1, "one")] as TKey[]
    def tk6 = [new TKey(6 - 31, 6, "six")] as TKey[]
    def tk10 = [new TKey(10 - 31, 10, "ten")] as TKey[]
    // insert some values that happen to be the chain of hashes 1 -> 6 -> 10
    fsCache.computeIfAbsent(tk1, tvc)
    fsCache.computeIfAbsent(tk6, tvc)
    fsCache.computeIfAbsent(tk10, tvc)

    expect:
    fsCache.computeIfAbsent(tk, tvc) == value
    creationCount.get() == count

    where:
    tk                                         | value          | count
    [new TKey(1 - 31, 1, "foo")] as TKey[]     | "one_value"    | 3     // used the cached tk1
    [new TKey(1 - 31, 6, "foo")] as TKey[]     | "six_value"    | 3     // used the cached tk6
    [new TKey(1 - 31, 10, "foo")] as TKey[]    | "ten_value"    | 3     // used the cached tk10
    [new TKey(6 - 31, 6, "foo")] as TKey[]     | "six_value"    | 3     // used the cached tk6
    [new TKey(1 - 31, 11, "eleven")] as TKey[] | "eleven_value" | 4     // create new value in an occupied slot
    [new TKey(4 - 31, 4, "four")] as TKey[]    | "four_value"   | 4     // create new value in empty slot
    null                                       | null           | 3     // do nothing
  }

  @Shared id1 = new TKey(1, 1, "one")

  def "identity cache should store and retrieve values"() {
    setup:
    def fsCache = DDCaches.newFixedSizeIdentityCache(15)
    def creationCount = new AtomicInteger(0)
    def tvc = new TVC(creationCount)
    fsCache.computeIfAbsent(id1, tvc)

    // (only use one key because we can't control the identity hash: more keys might overwrite
    // an earlier slot if rehashing cycles to the same slots, breaking test assumption that all
    // the initial keys are allocated to distinct slots)

    expect:
    fsCache.computeIfAbsent(tk, tvc) == value
    creationCount.get() == count

    where:
    tk                        | value          | count
    id1                       | "one_value"    | 1     // used the cached id1
    new TKey(1, 1, "1")       | "1_value"      | 2     // create new value for key with different identity
    new TKey(6, 6, "6")       | "6_value"      | 2     // create new value for new key
    null                      | null           | 1     // do nothing
  }

  def "chm cache should store and retrieve values"() {
    setup:
    def fsCache = DDCaches.newUnboundedCache(15)
    def creationCount = new AtomicInteger(0)
    def tvc = new TVC(creationCount)
    def tk1 = new TKey(1, 1, "one")
    def tk6 = new TKey(6, 6, "six")
    def tk10 = new TKey(10, 10, "ten")
    fsCache.computeIfAbsent(tk1, tvc)
    fsCache.computeIfAbsent(tk6, tvc)
    fsCache.computeIfAbsent(tk10, tvc)

    expect:
    fsCache.computeIfAbsent(tk, tvc) == value
    creationCount.get() == count

    where:
    tk                        | value          | count
    new TKey(1, 1, "foo")     | "one_value"    | 3
    new TKey(1, 6, "foo")     | "foo_value"    | 4
    new TKey(1, 10, "foo")    | "foo_value"    | 4
    new TKey(6, 6, "foo")     | "six_value"    | 3
    new TKey(1, 11, "eleven") | "eleven_value" | 4
    new TKey(4, 4, "four")    | "four_value"   | 4
    null                      | null           | 3
  }

  def "should handle concurrent usage"() {
    setup:
    def numThreads = 5
    def numInsertions = 100000
    def conds = new AsyncConditions(numThreads)
    def started = new CountDownLatch(numThreads)
    def runTest = new CountDownLatch(1)

    when:
    def fsCache = cacheImpl(64)
    for (int t = 0; t < numThreads; t++) {
      Thread.start {
        def tlr = ThreadLocalRandom.current()
        started.countDown()
        runTest.await()
        conds.evaluate {
          for (int i = 0; i < numInsertions; i++) {
            def r = tlr.nextInt()
            def s = String.valueOf(r)
            def tkey = new TKey(r, r, s)
            assert fsCache.computeIfAbsent(tkey, { k -> k.string + "_value" }) == s + "_value"
          }
        }
      }
    }
    started.await()
    runTest.countDown()

    then:
    conds.await(30.0) // the test is really fast locally, but I don't know how fast CI is

    where:
    cacheImpl << [
      { capacity -> DDCaches.newFixedSizeCache(capacity) },
      { capacity ->
        DDCaches.newUnboundedCache(capacity) }
    ]
  }

  def "fixed size partial key should store and retrieve values"() {
    setup:
    def fsCache = DDCaches.newFixedSizePartialKeyCache(15)
    def creationCount = new AtomicInteger(0)
    def tkbh = new TKeyBHash()
    def tkbc = new TKeyBComparator()
    def tkbp = new TKeyBProducer(creationCount)
    // we will use m and n in the hash, compare and produce functions
    def tk1 = tKeyB(1, 17, 29, "one")
    def tk6 = tKeyB(1, 47, 11, "six")
    def tk10 = tKeyB(1, 10, 66, "ten")
    // insert some values that happen to be the chain of hashes 1 -> 6 -> 10
    fsCache.computeIfAbsent(tk1, 17, 29, tkbh, tkbc, tkbp)
    fsCache.computeIfAbsent(tk6, 47, 11, tkbh, tkbc, tkbp)
    fsCache.computeIfAbsent(tk10, 10, 66, tkbh, tkbc, tkbp)
    def tk = h == 0 ? null : tKeyB(h, m, n, s)

    when:
    def v1 = fsCache.computeIfAbsent(tk, m, n, tkbh, tkbc, tkbp)

    then:
    v1 == value
    creationCount.get() == count
    fsCache.computeIfAbsent(tk1, 17, 29, tkbh, tkbc, tkbp) == "one_17_29"
    fsCache.computeIfAbsent(tk6, 47, 11, tkbh, tkbc, tkbp) == "six_47_11"
    fsCache.computeIfAbsent(tk10, 10, 66, tkbh, tkbc, tkbp) == "ten_10_66"
    creationCount.get() == count + (overwritten ? 1 : 0)

    where:
    h | m      | n     | s        | value          | overwritten | count
    1 |  18527 | 76397 | "foo"    | "one_17_29"    | false       | 3     // used the cached tk1
    1 | -14685 | 55725 | "foo"    | "six_47_11"    | false       | 3     // used the cached tk6
    1 | -19379 | 1712  | "foo"    | "ten_10_66"    | false       | 3     // used the cached tk10
    1 |  13    | 37    | "eleven" | "eleven_13_37" | true        | 4     // create new value in 1st occupied slot
    4 |  88    | 11    | "four"   | "four_88_11"   | false       | 4     // create new value in empty slot
    0 |  0     | 0     | null     | null           | false       | 3     // do nothing
  }

  private static TKeyB tKeyB(int hash, int m, int n, String s) {
    new TKeyB((hash * m) + n, s)
  }

  private static class TVC implements Function<TKey, String> {
    private final AtomicInteger count

    TVC(AtomicInteger count) {
      this.count = count
    }

    @Override
    String apply(TKey key) {
      count.incrementAndGet()
      return key.string + "_value"
    }
  }

  private class TVCA implements Function<TKey[], String> {
    private final AtomicInteger count

    TVCA(AtomicInteger count) {
      this.count = count
    }

    @Override
    String apply(TKey[] key) {
      count.incrementAndGet()
      return key[0].string + "_value"
    }
  }

  private static class TKeyBHash implements DDPartialKeyCache.Hasher<TKeyB> {
    @Override
    int apply(TKeyB key, int m, int n) {
      return (key.hash - n) / m
    }
  }

  private static class TKeyBComparator implements DDPartialKeyCache.Comparator<TKeyB, String> {
    @Override
    boolean test(TKeyB key, int m, int n, String s) {
      return "${key.string}_${m}_${n}" == s || (key.string.hashCode() * m) + n == s.hashCode()
    }
  }

  private static class TKeyBProducer implements DDPartialKeyCache.Producer<TKeyB, String> {
    private final AtomicInteger count

    TKeyBProducer(AtomicInteger count) {
      this.count = count
    }

    @Override
    String apply(TKeyB key, int m, int n) {
      count.incrementAndGet()
      return "${key.string}_${m}_${n}"
    }
  }

  private static class TKeyB {
    protected final int hash
    protected final String string

    TKeyB(int hash, String string) {
      this.hash = hash
      this.string = string
    }
  }

  private static class TKey extends TKeyB {
    private final int eq

    TKey(int hash, int eq, String string) {
      super(hash, string)
      this.eq = eq
    }

    boolean equals(o) {
      if (getClass() != o.class) {
        return false
      }

      TKey tKey = (TKey) o

      return eq == tKey.eq
    }

    int hashCode() {
      return hash
    }

    String toString() {
      return string
    }
  }
}
