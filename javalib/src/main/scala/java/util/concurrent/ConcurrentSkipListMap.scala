package java.util.concurrent

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.io.Serializable;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.SortedMap;
import java.util.Spliterator;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.concurrent.atomic.LongAdder;

/*
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

/** A scalable concurrent {@link ConcurrentNavigableMap} implementation. The map
 *  is sorted according to the {@linkplain Comparable natural ordering} of its
 *  keys, or by a {@link Comparator} provided at map creation time, depending on
 *  which constructor is used.
 *
 *  <p>This class implements a concurrent variant of <a
 *  href="http://en.wikipedia.org/wiki/Skip_list" target="_top">SkipLists</a>
 *  providing expected average <i>log(n)</i> time cost for the {@code
 *  containsKey}, {@code get}, {@code put} and {@code remove} operations and
 *  their variants. Insertion, removal, update, and access operations safely
 *  execute concurrently by multiple threads.
 *
 *  <p>Iterators and spliterators are <a
 *  href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
 *
 *  <p>Ascending key ordered views and their iterators are faster than
 *  descending ones.
 *
 *  <p>All {@code Map.Entry} pairs returned by methods in this class and its
 *  views represent snapshots of mappings at the time they were produced. They
 *  do <em>not</em> support the {@code Entry.setValue} method. (Note however
 *  that it is possible to change mappings in the associated map using {@code
 *  put}, {@code putIfAbsent}, or {@code replace}, depending on exactly which
 *  effect you need.)
 *
 *  <p>Beware that bulk operations {@code putAll}, {@code equals}, {@code
 *  toArray}, {@code containsValue}, and {@code clear} are <em>not</em>
 *  guaranteed to be performed atomically. For example, an iterator operating
 *  concurrently with a {@code putAll} operation might view only some of the
 *  added elements.
 *
 *  <p>This class and its views and iterators implement all of the
 *  <em>optional</em> methods of the {@link Map} and {@link Iterator}
 *  interfaces. Like most other concurrent collections, this class does
 *  <em>not</em> permit the use of {@code null} keys or values because some null
 *  return values cannot be reliably distinguished from the absence of elements.
 *
 *  <p>This class is a member of the <a href="{@docRoot
 *  }/java.base/java/util/package-summary.html#CollectionsFramework"> Java
 *  Collections Framework</a>.
 *
 *  @author
 *    Doug Lea
 *  @param <
 *    K> the type of keys maintained by this map
 *  @param <
 *    V> the type of mapped values
 *  @since 1.6
 */
@SerialVersionUID(-8627078645895051609L)
object ConcurrentSkipListMap {

  /** Nodes hold keys and values, and are singly linked in sorted order,
   *  possibly with some intervening marker nodes. The list is headed by a
   *  header node accessible as head.node. Headers and marker nodes have null
   *  keys. The val field (but currently not the key field) is nulled out upon
   *  deletion.
   */
  final private[concurrent] class Node[K, V] private[concurrent] (
      val key: K // currently, never detached
      ,
      var `val`: V,
      var next: ConcurrentSkipListMap.Node[K, V]
  ) {}

  /** Index nodes represent the levels of the skip list.
   */
  final private[concurrent] class Index[K, V] private[concurrent] (
      val node: ConcurrentSkipListMap.Node[K, V],
      val down: ConcurrentSkipListMap.Index[K, V],
      var right: ConcurrentSkipListMap.Index[K, V]
  ) {}

  /** Compares using comparator or natural ordering if null. Called only by
   *  methods that have performed required type checks.
   */
  @SuppressWarnings(
    Array("unchecked", "rawtypes")
  ) private[concurrent] def cpr[T <: Any](
      c: Comparator[T],
      x: T,
      y: T
  ): Int = {
    return if ((c != null)) {
      c.compare(
        x,
        y
      )
    } else {
      (x.asInstanceOf[Comparable[T]]).compareTo(y)
    }
  }

  /** Tries to unlink deleted node n from predecessor b (if both exist), by
   *  first splicing in a marker if not already present. Upon return, node n is
   *  sure to be unlinked from b, possibly via the actions of some other thread.
   *
   *  @param b
   *    if nonnull, predecessor
   *  @param n
   *    if nonnull, node known to be deleted
   */
  private[concurrent] def unlinkNode[K, V](
      b: ConcurrentSkipListMap.Node[K, V],
      n: ConcurrentSkipListMap.Node[K, V]
  ): Unit = {
    if (b != null && n != null) {
      var f: ConcurrentSkipListMap.Node[K, V] = null
      var p: ConcurrentSkipListMap.Node[K, V] = null

      var continue = true
      while ({
        continue
      }) {
        if ((f = n.next) != null && f.key == null) {
          p = f.next // already marked

          continue = false
        } else {
          if (NEXT.compareAndSet(
                n,
                f,
                new ConcurrentSkipListMap.Node[K, V](
                  null.asInstanceOf[K],
                  null.asInstanceOf[V],
                  f
                )
              )) {
            p = f // add marker

            continue = false
          }
        }
      }
      NEXT.compareAndSet(b, n, p)
    }
  }

  /** Add indices after an insertion. Descends iteratively to the highest level
   *  of insertion, then recursively, to chain index nodes to lower ones.
   *  Returns null on (staleness) failure, disabling higher-level insertions.
   *  Recursion depths are exponentially less probable.
   *
   *  @param q
   *    starting index for current level
   *  @param skips
   *    levels to skip before inserting
   *  @param x
   *    index for this insertion
   *  @param cmp
   *    comparator
   */
  private[concurrent] def addIndices[K, V](
      q: ConcurrentSkipListMap.Index[K, V],
      skips: Int,
      x: ConcurrentSkipListMap.Index[K, V],
      cmp: Comparator[_ >: K]
  ): Boolean = {
    var _q = q
    var _skips = skips

    var z: ConcurrentSkipListMap.Node[K, V] = null
    var key: K = null.asInstanceOf[K]
    if (x != null && (z = x.node) != null && (key =
          z.key) != null && _q != null) { // hoist checks
      var retrying: Boolean = false

      var continue = true
      while ({
        continue
      }) { // find splice point
        var r: ConcurrentSkipListMap.Index[K, V] = null
        var d: ConcurrentSkipListMap.Index[K, V] = null
        var c: Int = 0
        if ((r = _q.right) != null) {
          var p: ConcurrentSkipListMap.Node[K, V] = null
          var k: K = null.asInstanceOf[K]
          if ((p = r.node) == null || (k = p.key) == null || p.`val` == null) {
            RIGHT.compareAndSet(_q, r, r.right)
            c = 0
          } else {
            if ({ c = ConcurrentSkipListMap.cpr(cmp, key, k); c > 0 }) {
              _q = r
            } else {
              if (c == 0) {
                continue = false
                // stale
              }
            }
          }
        } else {
          c = -(1)
        }
        if (c < 0 && continue == true) {
          if ((d = _q.down) != null && _skips > 0) {
            _skips = _skips - 1
            _q = d
          } else {
            if (d != null && !(retrying) && !(addIndices(d, 0, x.down, cmp))) {
              continue = false
            } else {
              x.right = r
              if (RIGHT.compareAndSet(_q, r, x)) {
                return true
              } else {
                retrying = true // re-find splice point
              }
            }
          }
        }
      }
    }
    return false
  }

  private val EQ: Int = 1
  private val LT: Int = 2
  private val GT: Int = 0 // Actually checked as !LT

  private[concurrent] def toList[E](c: Collection[E]): List[E] = { // Using size() here would be a pessimization.
    val list: ArrayList[E] = new ArrayList[E]
    c.forEach { e =>
      list.add(e)
    }
    return list
  }

  final private[concurrent] class KeySet[K, V] private[concurrent] (
      val m: ConcurrentNavigableMap[K, V]
  ) extends AbstractSet[K]
      with NavigableSet[K] {
    override def size: Int = {
      return m.size()
    }

    override def isEmpty: Boolean = {
      return m.isEmpty()
    }

    override def contains(o: Any): Boolean = {
      return m.containsKey(o)
    }

    override def remove(o: Any): Boolean = {
      return m.remove(o) != null
    }

    override def clear(): Unit = {
      m.clear()
    }

    override def lower(e: K): K = {
      return m.lowerKey(e)
    }

    override def floor(e: K): K = {
      return m.floorKey(e)
    }

    override def ceiling(e: K): K = {
      return m.ceilingKey(e)
    }

    override def higher(e: K): K = {
      return m.higherKey(e)
    }

    override def comparator: Comparator[_ >: K] = {
      return m.comparator
    }

    override def first: K = {
      return m.firstKey
    }

    override def last: K = {
      return m.lastKey
    }

    override def pollFirst: K = {
      val e: Map.Entry[K, V] = m.pollFirstEntry
      return if ((e == null)) {
        null.asInstanceOf[K]
      } else {
        e.getKey
      }
    }

    override def pollLast: K = {
      val e: Map.Entry[K, V] = m.pollLastEntry
      return if ((e == null)) {
        null.asInstanceOf[K]
      } else {
        e.getKey
      }
    }

    override def iterator: Iterator[K] = {
      return if ((m.isInstanceOf[ConcurrentSkipListMap[_, _]])) {
        val _m = m.asInstanceOf[ConcurrentSkipListMap[K, V]]
        new _m.KeyIterator
      } else {
        val _m = m.asInstanceOf[SubMap[K, V]]
        new _m.SubMapKeyIterator
      }
    }

    override def equals(o: Any): Boolean = {
      if (o == this) {
        return true
      }
      if (!((o.isInstanceOf[Set[_]]))) {
        return false
      }
      val c: Collection[_] = o.asInstanceOf[Collection[_]]
      try return containsAll(c) && c.containsAll(this)
      catch {
        case unused @ (_: ClassCastException | _: NullPointerException) =>
          return false
      }
    }

    override def toArray: Array[AnyRef] = {
      return toList(this).toArray
    }

    override def toArray[T](a: Array[T]): Array[T] = {
      return toList(this).toArray(a)
    }

    override def descendingIterator: Iterator[K] = {
      return descendingSet.iterator
    }

    override def subSet(
        fromElement: K,
        fromInclusive: Boolean,
        toElement: K,
        toInclusive: Boolean
    ): NavigableSet[K] = {
      return new ConcurrentSkipListMap.KeySet[K, V](
        m.subMap(fromElement, fromInclusive, toElement, toInclusive)
      )
    }

    override def headSet(
        toElement: K,
        inclusive: Boolean
    ): NavigableSet[K] = {
      return new ConcurrentSkipListMap.KeySet[K, V](
        m.headMap(toElement, inclusive)
      )
    }

    override def tailSet(
        fromElement: K,
        inclusive: Boolean
    ): NavigableSet[K] = {
      return new ConcurrentSkipListMap.KeySet[K, V](
        m.tailMap(fromElement, inclusive)
      )
    }

    override def subSet(fromElement: K, toElement: K): NavigableSet[K] = {
      return subSet(fromElement, true, toElement, false)
    }

    override def headSet(toElement: K): NavigableSet[K] = {
      return headSet(toElement, false)
    }

    override def tailSet(fromElement: K): NavigableSet[K] = {
      return tailSet(fromElement, true)
    }

    override def descendingSet: NavigableSet[K] = {
      return new ConcurrentSkipListMap.KeySet[K, V](m.descendingMap)
    }

    override def spliterator: Spliterator[K] = {
      return if ((m.isInstanceOf[ConcurrentSkipListMap[_, _]])) {
        val _m = m.asInstanceOf[ConcurrentSkipListMap[K, V]]
        _m.keySpliterator
      } else {
        val _m = m.asInstanceOf[SubMap[K, V]]
        new _m.SubMapKeyIterator
      }
    }
  }

  final private[concurrent] class Values[K, V] private[concurrent] (
      val m: ConcurrentNavigableMap[K, V]
  ) extends AbstractCollection[V] {
    override def iterator: Iterator[V] = {
      return if ((m.isInstanceOf[ConcurrentSkipListMap[_, _]])) {
        val _m = m.asInstanceOf[ConcurrentSkipListMap[K, V]]
        new _m.ValueIterator
      } else {
        val _m = m.asInstanceOf[SubMap[K, V]]
        new _m.SubMapValueIterator
      }
    }

    override def size: Int = {
      return m.size
    }

    override def isEmpty: Boolean = {
      return m.isEmpty
    }

    override def contains(o: Any): Boolean = {
      return m.containsValue(o)
    }

    override def clear(): Unit = {
      m.clear()
    }

    override def toArray: Array[AnyRef] = {
      return toList(this).toArray
    }

    override def toArray[T](a: Array[T]): Array[T] = {
      return toList(this).toArray(a)
    }

    override def spliterator: Spliterator[V] = {
      return if ((m.isInstanceOf[ConcurrentSkipListMap[_, _]])) {
        (m.asInstanceOf[ConcurrentSkipListMap[K, V]]).valueSpliterator
      } else {
        val _m = m.asInstanceOf[SubMap[K, V]]
        new _m.SubMapValueIterator
      }
    }

    override def removeIf(filter: Predicate[_ >: V]): Boolean = {
      if (filter == null) {
        throw new NullPointerException
      }
      if (m.isInstanceOf[ConcurrentSkipListMap[_, _]]) {
        return (m
          .asInstanceOf[ConcurrentSkipListMap[K, V]])
          .removeValueIf(filter)
      }
      // else use iterator
      val it: Iterator[Map.Entry[K, V]] = {
        val s = m.asInstanceOf[SubMap[K, V]]
        new s.SubMapEntryIterator
      }
      var removed: Boolean = false
      while ({
        it.hasNext
      }) {
        val e: Map.Entry[K, V] = it.next
        val v: V = e.getValue
        if (filter.test(v) && m.remove(e.getKey, v)) {
          removed = true
        }
      }
      return removed
    }
  }

  final private[concurrent] class EntrySet[K, V] private[concurrent] (
      val m: ConcurrentNavigableMap[K, V]
  ) extends AbstractSet[Map.Entry[K, V]] {
    override def iterator: Iterator[Map.Entry[K, V]] = {
      return if ((m.isInstanceOf[ConcurrentSkipListMap[_, _]])) {
        val _m = m.asInstanceOf[ConcurrentSkipListMap[K, V]]
        new _m.EntryIterator
      } else {
        val _m = m.asInstanceOf[SubMap[K, V]]
        new _m.SubMapEntryIterator
      }
    }

    override def contains(o: Any): Boolean = {
      if (!((o.isInstanceOf[Map.Entry[_, _]]))) {
        return false
      }
      val e: Map.Entry[_, _] = o.asInstanceOf[Map.Entry[_, _]]
      val v: V = m.get(e.getKey)
      return v != null && v == e.getValue
    }

    override def remove(o: Any): Boolean = {
      if (!((o.isInstanceOf[Map.Entry[_, _]]))) {
        return false
      }
      val e: Map.Entry[_, _] = o.asInstanceOf[Map.Entry[_, _]]
      return m.remove(e.getKey, e.getValue)
    }

    override def isEmpty: Boolean = {
      return m.isEmpty
    }

    override def size: Int = {
      return m.size
    }

    override def clear(): Unit = {
      m.clear()
    }

    override def equals(o: Any): Boolean = {
      if (o eq this) {
        return true
      }
      if (!((o.isInstanceOf[Set[_]]))) {
        return false
      }
      val c: Collection[_] = o.asInstanceOf[Collection[_]]
      try return containsAll(c) && c.containsAll(this)
      catch {
        case unused @ (_: ClassCastException | _: NullPointerException) =>
          return false
      }
    }

    override def toArray: Array[AnyRef] = {
      return toList(this).toArray
    }

    override def toArray[T](a: Array[T]): Array[T] = {
      return toList(this).toArray(a)
    }

    override def spliterator: Spliterator[Map.Entry[K, V]] = {
      return if ((m.isInstanceOf[ConcurrentSkipListMap[_, _]])) {
        (m.asInstanceOf[ConcurrentSkipListMap[K, V]]).entrySpliterator
      } else {
        val _m = m.asInstanceOf[SubMap[K, V]]
        new _m.SubMapEntryIterator
      }
    }

    override def removeIf(
        filter: Predicate[_ >: Map.Entry[K, V]]
    ): Boolean = {
      if (filter == null) {
        throw new NullPointerException
      }
      if (m.isInstanceOf[ConcurrentSkipListMap[_, _]]) {
        return (m
          .asInstanceOf[ConcurrentSkipListMap[K, V]])
          .removeEntryIf(filter)
      }
      val it: Iterator[Map.Entry[K, V]] = {
        val s = m.asInstanceOf[SubMap[K, V]]
        new s.SubMapEntryIterator
      }
      var removed: Boolean = false
      while ({
        it.hasNext
      }) {
        val e: Map.Entry[K, V] = it.next
        if (filter.test(e) && m.remove(e.getKey, e.getValue)) {
          removed = true
        }
      }
      return removed
    }
  }

  /** Submaps returned by {@link ConcurrentSkipListMap} submap operations
   *  represent a subrange of mappings of their underlying maps. Instances of
   *  this class support all methods of their underlying maps, differing in that
   *  mappings outside their range are ignored, and attempts to add mappings
   *  outside their ranges result in {@link IllegalArgumentException}. Instances
   *  of this class are constructed only using the {@code subMap}, {@code
   *  headMap}, and {@code tailMap} methods of their underlying maps.
   *
   *  @serial
   *    include
   */
  @SerialVersionUID(-7647078645895051609L)
  final private[concurrent] class SubMap[K, V] private[concurrent] (
      /** Underlying map */
      val m: ConcurrentSkipListMap[K, V],

      /** lower bound key, or null if from start */
      val lo: K,

      /** inclusion flag for lo */
      val loInclusive: Boolean,

      /** upper bound key, or null if to end */
      val hi: K,

      /** inclusion flag for hi */
      val hiInclusive: Boolean,

      /** direction */
      val isDescending: Boolean
  )

  /** Creates a new submap, initializing all fields.
   */
      extends AbstractMap[K, V]
      with ConcurrentNavigableMap[K, V]
      with Serializable {
    val cmp: Comparator[_ >: K] = m.comparator
    if (lo != null && hi != null && ConcurrentSkipListMap.cpr(
          cmp,
          lo,
          hi
        ) > 0) {
      throw new IllegalArgumentException("inconsistent range")
    }
    // Lazily initialized view holders
    private var keySetView: ConcurrentSkipListMap.KeySet[K, V] = null
    private var valuesView: ConcurrentSkipListMap.Values[K, V] = null
    private var entrySetView: ConcurrentSkipListMap.EntrySet[K, V] = null

    private[concurrent] def tooLow(
        key: Any,
        cmp: Comparator[_ >: K]
    ): Boolean = {
      var c: Int = 0
      return (lo != null && ((c = ConcurrentSkipListMap.cpr(
        cmp,
        key,
        lo
      )) < 0 || (c == 0 && !(loInclusive))))
    }

    private[concurrent] def tooHigh(
        key: Any,
        cmp: Comparator[_ >: K]
    ): Boolean = {
      var c: Int = 0
      return (hi != null && ((c = ConcurrentSkipListMap.cpr(
        cmp,
        key,
        hi
      )) > 0 || (c == 0 && !(hiInclusive))))
    }

    private[concurrent] def inBounds(
        key: Any,
        cmp: Comparator[_ >: K]
    ): Boolean = {
      return !(tooLow(key, cmp)) && !(tooHigh(key, cmp))
    }

    private[concurrent] def checkKeyBounds(
        key: K,
        cmp: Comparator[_ >: K]
    ): Unit = {
      if (key == null) {
        throw new NullPointerException
      }
      if (!(inBounds(key, cmp))) {
        throw new IllegalArgumentException("key out of range")
      }
    }

    /** Returns true if node key is less than upper bound of range.
     */
    private[concurrent] def isBeforeEnd(
        n: ConcurrentSkipListMap.Node[K, V],
        cmp: Comparator[_ >: K]
    ): Boolean = {
      if (n == null) {
        return false
      }
      if (hi == null) {
        return true
      }
      val k: K = n.key
      if (k == null) { // pass by markers and headers
        return true
      }
      val c: Int = ConcurrentSkipListMap.cpr(cmp, k, hi)
      return c < 0 || (c == 0 && hiInclusive)
    }

    /** Returns lowest node. This node might not be in range, so most usages
     *  need to check bounds.
     */
    private[concurrent] def loNode(
        cmp: Comparator[_ >: K]
    ): ConcurrentSkipListMap.Node[K, V] = {
      if (lo == null) {
        return m.findFirst
      } else {
        if (loInclusive) {
          return m.findNear(lo, GT | EQ, cmp)
        } else {
          return m.findNear(lo, GT, cmp)
        }
      }
    }

    /** Returns highest node. This node might not be in range, so most usages
     *  need to check bounds.
     */
    private[concurrent] def hiNode(
        cmp: Comparator[_ >: K]
    ): ConcurrentSkipListMap.Node[K, V] = {
      if (hi == null) {
        return m.findLast
      } else {
        if (hiInclusive) {
          return m.findNear(hi, LT | EQ, cmp)
        } else {
          return m.findNear(hi, LT, cmp)
        }
      }
    }

    /** Returns lowest absolute key (ignoring directionality).
     */
    private[concurrent] def lowestKey: K = {
      val cmp: Comparator[_ >: K] = m.comparator
      val n: ConcurrentSkipListMap.Node[K, V] = loNode(cmp)
      if (isBeforeEnd(n, cmp)) {
        return n.key
      } else {
        throw new NoSuchElementException
      }
    }

    /** Returns highest absolute key (ignoring directionality).
     */
    private[concurrent] def highestKey: K = {
      val cmp: Comparator[_ >: K] = m.comparator
      val n: ConcurrentSkipListMap.Node[K, V] = hiNode(cmp)
      if (n != null) {
        val last: K = n.key
        if (inBounds(last, cmp)) {
          return last
        }
      }
      throw new NoSuchElementException
    }

    private[concurrent] def lowestEntry: Map.Entry[K, V] = {
      val cmp: Comparator[_ >: K] = m.comparator

      while ({
        true
      }) {
        var n: ConcurrentSkipListMap.Node[K, V] = null
        var v: V = null.asInstanceOf[V]
        if ((n = loNode(cmp)) == null || !(isBeforeEnd(n, cmp))) {
          return null
        } else {
          if ((v = n.`val`) != null) {
            return new AbstractMap.SimpleImmutableEntry[K, V](n.key, v)
          }
        }
      }
    }

    private[concurrent] def highestEntry: Entry[K, V] = {
      val cmp: Comparator[_ >: K] = m.comparator

      while ({
        true
      }) {
        var n: ConcurrentSkipListMap.Node[K, V] = null
        var v: V = null.asInstanceOf[V]
        if ((n = hiNode(cmp)) == null || !(inBounds(n.key, cmp))) {
          return null
        } else {
          if ((v = n.`val`) != null) {
            return new AbstractMap.SimpleImmutableEntry[K, V](n.key, v)
          }
        }
      }
    }

    private[concurrent] def removeLowest: Entry[K, V] = {
      val cmp: Comparator[_ >: K] = m.comparator

      while ({
        true
      }) {
        var n: ConcurrentSkipListMap.Node[K, V] = null
        var k: K = null.asInstanceOf[K]
        var v: V = null.asInstanceOf[V]
        if ((n = loNode(cmp)) == null) {
          return null
        } else {
          if (!(inBounds((k = n.key), cmp))) {
            return null
          } else {
            if ((v = m.doRemove(k, null)) != null) {
              return new AbstractMap.SimpleImmutableEntry[K, V](k, v)
            }
          }
        }
      }
    }

    private[concurrent] def removeHighest: Map.Entry[K, V] = {
      val cmp: Comparator[_ >: K] = m.comparator

      while ({
        true
      }) {
        var n: ConcurrentSkipListMap.Node[K, V] = null
        var k: K = null.asInstanceOf[K]
        var v: V = null.asInstanceOf[V]
        if ((n = hiNode(cmp)) == null) {
          return null
        } else {
          if (!(inBounds((k = n.key), cmp))) {
            return null
          } else {
            if ((v = m.doRemove(k, null)) != null) {
              return new AbstractMap.SimpleImmutableEntry[K, V](k, v)
            }
          }
        }
      }
    }

    /** Submap version of ConcurrentSkipListMap.findNearEntry.
     */
    private[concurrent] def getNearEntry(
        key: K,
        rel: Int
    ): Map.Entry[K, V] = {
      val cmp: Comparator[_ >: K] = m.comparator
      if (isDescending) { // adjust relation for direction
        if ((rel & LT) == 0) {
          rel |= LT
        } else {
          rel &= ~(LT)
        }
      }
      if (tooLow(key, cmp)) {
        return if (((rel & LT) != 0)) {
          null
        } else {
          lowestEntry
        }
      }
      if (tooHigh(key, cmp)) {
        return if (((rel & LT) != 0)) {
          highestEntry
        } else {
          null
        }
      }
      val e: AbstractMap.SimpleImmutableEntry[K, V] =
        m.findNearEntry(key, rel, cmp)
      if (e == null || !(inBounds(e.getKey, cmp))) {
        return null
      } else {
        return e
      }
    }

    // Almost the same as getNearEntry, except for keys
    private[concurrent] def getNearKey(key: K, rel: Int): K = {
      val cmp: Comparator[_ >: K] = m.comparator
      if (isDescending) {
        if ((rel & LT) == 0) {
          rel |= LT
        } else {
          rel &= ~(LT)
        }
      }
      if (tooLow(key, cmp)) {
        if ((rel & LT) == 0) {
          val n: ConcurrentSkipListMap.Node[K, V] = loNode(cmp)
          if (isBeforeEnd(n, cmp)) {
            return n.key
          }
        }
        return null.asInstanceOf[K]
      }
      if (tooHigh(key, cmp)) {
        if ((rel & LT) != 0) {
          val n: ConcurrentSkipListMap.Node[K, V] = hiNode(cmp)
          if (n != null) {
            val last: K = n.key
            if (inBounds(last, cmp)) {
              return last
            }
          }
        }
        return null.asInstanceOf[K]
      }

      while ({
        true
      }) {
        val n: ConcurrentSkipListMap.Node[K, V] = m.findNear(key, rel, cmp)
        if (n == null || !(inBounds(n.key, cmp))) {
          return null.asInstanceOf[K]
        }
        if (n.`val` != null) {
          return n.key
        }
      }
    }

    override def containsKey(key: Any): Boolean = {
      if (key == null) {
        throw new NullPointerException
      }
      return inBounds(key, m.comparator) && m.containsKey(key)
    }

    override def get(key: Any): V = {
      if (key == null) {
        throw new NullPointerException
      }
      return if ((!(inBounds(key, m.comparator)))) {
        null.asInstanceOf
      } else {
        m.get(key)
      }
    }

    override def put(key: K, value: V): V = {
      checkKeyBounds(key, m.comparator)
      return m.put(key, value)
    }

    override def remove(key: Any): V = {
      return if ((!(inBounds(key, m.comparator)))) {
        null.asInstanceOf[V]
      } else {
        m.remove(key)
      }
    }

    override def size: Int = {
      val cmp: Comparator[_ >: K] = m.comparator
      var count: Long = 0
      var n: ConcurrentSkipListMap.Node[K, V] = loNode(cmp)
      while ({
        isBeforeEnd(n, cmp)
      }) {
        if (n.`val` != null) {
          count += 1
        }

        n = n.next
      }
      return if (count >= Integer.MAX_VALUE) {
        Integer.MAX_VALUE
      } else {
        count.toInt
      }
    }

    override def isEmpty: Boolean = {
      val cmp: Comparator[_ >: K] = m.comparator
      return !(isBeforeEnd(loNode(cmp), cmp))
    }

    override def containsValue(value: Any): Boolean = {
      if (value == null) {
        throw new NullPointerException
      }
      val cmp: Comparator[_ >: K] = m.comparator
      var n: ConcurrentSkipListMap.Node[K, V] = loNode(cmp)
      while ({
        isBeforeEnd(n, cmp)
      }) {
        val v: V = n.`val`
        if (v != null && value == v) {
          return true
        }

        n = n.next
      }
      return false
    }

    override def clear(): Unit = {
      val cmp: Comparator[_ >: K] = m.comparator
      var n: ConcurrentSkipListMap.Node[K, V] = loNode(cmp)
      while ({
        isBeforeEnd(n, cmp)
      }) {
        if (n.`val` != null) {
          m.remove(n.key)
        }

        n = n.next
      }
    }

    override def putIfAbsent(key: K, value: V): V = {
      checkKeyBounds(key, m.comparator)
      return m.putIfAbsent(key, value)
    }

    override def remove(key: Any, value: Any): Boolean = {
      return inBounds(key, m.comparator) && m.remove(key, value)
    }

    override def replace(key: K, oldValue: V, newValue: V): Boolean = {
      checkKeyBounds(key, m.comparator)
      return m.replace(key, oldValue, newValue)
    }

    override def replace(key: K, value: V): V = {
      checkKeyBounds(key, m.comparator)
      return m.replace(key, value)
    }

    override def comparator: Comparator[_ >: K] = {
      val cmp: Comparator[_ >: K] = m.comparator
      if (isDescending) {
        return Collections.reverseOrder(cmp)
      } else {
        return cmp
      }
    }

    /** Utility to create submaps, where given bounds override unbounded(null)
     *  ones and/or are checked against bounded ones.
     */
    private[concurrent] def newSubMap(
        fromKey: K,
        fromInclusive: Boolean,
        toKey: K,
        toInclusive: Boolean
    ): ConcurrentSkipListMap.SubMap[K, V] = {
      val cmp: Comparator[_ >: K] = m.comparator
      if (isDescending) { // flip senses
        val tk: K = fromKey
        fromKey = toKey
        toKey = tk
        val ti: Boolean = fromInclusive
        fromInclusive = toInclusive
        toInclusive = ti
      }
      if (lo != null) {
        if (fromKey == null) {
          fromKey = lo
          fromInclusive = loInclusive
        } else {
          val c: Int = ConcurrentSkipListMap.cpr(cmp, fromKey, lo)
          if (c < 0 || (c == 0 && !(loInclusive) && fromInclusive)) {
            throw new IllegalArgumentException("key out of range")
          }
        }
      }
      if (hi != null) {
        if (toKey == null) {
          toKey = hi
          toInclusive = hiInclusive
        } else {
          val c: Int = ConcurrentSkipListMap.cpr(cmp, toKey, hi)
          if (c > 0 || (c == 0 && !(hiInclusive) && toInclusive)) {
            throw new IllegalArgumentException("key out of range")
          }
        }
      }
      return new ConcurrentSkipListMap.SubMap[K, V](
        m,
        fromKey,
        fromInclusive,
        toKey,
        toInclusive,
        isDescending
      )
    }

    override def subMap(
        fromKey: K,
        fromInclusive: Boolean,
        toKey: K,
        toInclusive: Boolean
    ): ConcurrentSkipListMap.SubMap[K, V] = {
      if (fromKey == null || toKey == null) {
        throw new NullPointerException
      }
      return newSubMap(fromKey, fromInclusive, toKey, toInclusive)
    }

    override def headMap(
        toKey: K,
        inclusive: Boolean
    ): ConcurrentSkipListMap.SubMap[K, V] = {
      if (toKey == null) {
        throw new NullPointerException
      }
      return newSubMap(null.asInstanceOf[K], false, toKey, inclusive)
    }

    override def tailMap(
        fromKey: K,
        inclusive: Boolean
    ): ConcurrentSkipListMap.SubMap[K, V] = {
      if (fromKey == null) {
        throw new NullPointerException
      }
      return newSubMap(fromKey, inclusive, null.asInstanceOf[K], false)
    }

    override def subMap(
        fromKey: K,
        toKey: K
    ): ConcurrentSkipListMap.SubMap[K, V] = {
      return subMap(fromKey, true, toKey, false)
    }

    override def headMap(toKey: K): ConcurrentSkipListMap.SubMap[K, V] = {
      return headMap(toKey, false)
    }

    override def tailMap(fromKey: K): ConcurrentSkipListMap.SubMap[K, V] = {
      return tailMap(fromKey, true)
    }

    override def descendingMap: ConcurrentSkipListMap.SubMap[K, V] = {
      return new ConcurrentSkipListMap.SubMap[K, V](
        m,
        lo,
        loInclusive,
        hi,
        hiInclusive,
        !(isDescending)
      )
    }

    override def ceilingEntry(key: K): Map.Entry[K, V] = {
      return getNearEntry(key, GT | EQ)
    }

    override def ceilingKey(key: K): K = {
      return getNearKey(key, GT | EQ)
    }

    override def lowerEntry(key: K): Map.Entry[K, V] = {
      return getNearEntry(key, LT)
    }

    override def lowerKey(key: K): K = {
      return getNearKey(key, LT)
    }

    override def floorEntry(key: K): Map.Entry[K, V] = {
      return getNearEntry(key, LT | EQ)
    }

    override def floorKey(key: K): K = {
      return getNearKey(key, LT | EQ)
    }

    override def higherEntry(key: K): Map.Entry[K, V] = {
      return getNearEntry(key, GT)
    }

    override def higherKey(key: K): K = {
      return getNearKey(key, GT)
    }

    override def firstKey: K = {
      return if (isDescending) {
        highestKey
      } else {
        lowestKey
      }
    }

    override def lastKey: K = {
      return if (isDescending) {
        lowestKey
      } else {
        highestKey
      }
    }

    override def firstEntry: Map.Entry[K, V] = {
      return if (isDescending) {
        highestEntry
      } else {
        lowestEntry
      }
    }

    override def lastEntry: Map.Entry[K, V] = {
      return if (isDescending) {
        lowestEntry
      } else {
        highestEntry
      }
    }

    override def pollFirstEntry: Map.Entry[K, V] = {
      return if (isDescending) {
        removeHighest
      } else {
        removeLowest
      }
    }

    override def pollLastEntry: Map.Entry[K, V] = {
      return if (isDescending) {
        removeLowest
      } else {
        removeHighest
      }
    }

    override def keySet: NavigableSet[K] = {
      var ks: ConcurrentSkipListMap.KeySet[K, V] = null
      if ((ks = keySetView) != null) {
        return ks
      }
      return keySetView = new ConcurrentSkipListMap.KeySet[K, V](this)
    }

    override def navigableKeySet: NavigableSet[K] = {
      var ks: ConcurrentSkipListMap.KeySet[K, V] = null
      if ((ks = keySetView) != null) {
        return ks
      }
      return keySetView = new ConcurrentSkipListMap.KeySet[K, V](this)
    }

    override def values: Collection[V] = {
      var vs: ConcurrentSkipListMap.Values[K, V] = null
      if ((vs = valuesView) != null) {
        return vs
      }
      return valuesView = new ConcurrentSkipListMap.Values[K, V](this)
    }

    override def entrySet: Set[Map.Entry[K, V]] = {
      var es: ConcurrentSkipListMap.EntrySet[K, V] = null
      if ((es = entrySetView) != null) {
        return es
      }
      return entrySetView = new ConcurrentSkipListMap.EntrySet[K, V](this)
    }

    override def descendingKeySet: NavigableSet[K] = {
      return descendingMap.navigableKeySet
    }

    /** Variant of main Iter class to traverse through submaps. Also serves as
     *  back-up Spliterator for views.
     */
    abstract private[concurrent] class SubMapIter[T] private[concurrent] ()
        extends Iterator[T]
        with Spliterator[T] {
      VarHandle.acquireFence()
      val cmp: Comparator[_ >: K] = m.comparator

      var continue = true
      while ({
        continue
      }) {
        next = if (isDescending) {
          hiNode(cmp)
        } else {
          loNode(cmp)
        }
        if (next == null) {
          continue = false
        }
        if (continue) {
          val x: V = next.`val`
          if (x != null) {
            if (!(inBounds(next.key, cmp))) {
              next = null
            } else {
              nextValue = x
            }
            continue = false
          }
        }
      }

      /** the last node returned by next() */
      private[concurrent] var lastReturned: ConcurrentSkipListMap.Node[K, V] =
        null

      /** the next node to return from next(); */
      private[concurrent] var next: ConcurrentSkipListMap.Node[K, V] = null

      /** Cache of next value field to maintain weak consistency */
      private[concurrent] var nextValue: V = null.asInstanceOf[V]

      override final def hasNext: Boolean = {
        return next != null
      }

      final private[concurrent] def advance(): Unit = {
        if (next == null) {
          throw new NoSuchElementException
        }
        lastReturned = next
        if (isDescending) {
          descend()
        } else {
          ascend()
        }
      }

      private def ascend(): Unit = {
        val cmp: Comparator[_ >: K] = m.comparator

        while ({
          true
        }) {
          next = next.next
          if (next == null) {
            break // todo: break is not supported
          }
          val x: V = next.`val`
          if (x != null) {
            if (tooHigh(next.key, cmp)) {
              next = null
            } else {
              nextValue = x
            }
            break // todo: break is not supported

          }
        }
      }

      private def descend(): Unit = {
        val cmp: Comparator[_ >: K] = m.comparator

        var continue = false
        while ({
          continue
        }) {
          next = m.findNear(lastReturned.key, LT, cmp)
          if (next == null) {
            continue = false
          }
          if (continue) {
            val x: V = next.`val`
            if (x != null) {
              if (tooLow(next.key, cmp)) {
                next = null
              } else {
                nextValue = x
              }
              continue = false
            }
          }
        }
      }

      override def remove(): Unit = {
        val l: ConcurrentSkipListMap.Node[K, V] = lastReturned
        if (l == null) {
          throw new IllegalStateException
        }
        m.remove(l.key)
        lastReturned = null
      }

      override def trySplit: Spliterator[T] = {
        return null
      }

      override def tryAdvance(action: Consumer[_ >: T]): Boolean = {
        if (hasNext) {
          action.accept(next)
          return true
        }
        return false
      }

      override def forEachRemaining(action: Consumer[_ >: T]): Unit = {
        while ({
          hasNext
        }) {
          action.accept(next)
        }
      }

      override def estimateSize: Long = {
        return Long.MAX_VALUE
      }
    }

    final private[concurrent] class SubMapValueIterator extends SubMapIter[V] {
      override def next: V = {
        val v: V = nextValue
        advance()
        return v
      }

      override def characteristics: Int = {
        return 0
      }
    }

    final private[concurrent] class SubMapKeyIterator extends SubMapIter[K] {
      override def next: K = {
        val n: ConcurrentSkipListMap.Node[K, V] = next
        advance()
        return n.key
      }

      override def characteristics: Int = {
        return Spliterator.DISTINCT | Spliterator.ORDERED | Spliterator.SORTED
      }

      override final def getComparator: Comparator[_ >: K] = {
        return thisSubMap.comparator
      }
    }

    final private[concurrent] class SubMapEntryIterator
        extends SubMapIter[
          Map.Entry[K, V]
        ] {
      override def next: Map.Entry[K, V] = {
        val n: ConcurrentSkipListMap.Node[K, V] = next
        val v: V = nextValue
        advance()
        return new AbstractMap.SimpleImmutableEntry[K, V](n.key, v)
      }

      override def characteristics: Int = {
        return Spliterator.DISTINCT
      }
    }
  }

  /** Base class providing common structure for Spliterators. (Although not all
   *  that much common functionality; as usual for view classes, details
   *  annoyingly vary in key, value, and entry subclasses in ways that are not
   *  worth abstracting out for internal classes.)
   *
   *  The basic split strategy is to recursively descend from top level, row by
   *  row, descending to next row when either split off, or the end of row is
   *  encountered. Control of the number of splits relies on some statistical
   *  estimation: The expected remaining number of elements of a skip list when
   *  advancing either across or down decreases by about 25%.
   */
  abstract private[concurrent] class CSLMSpliterator[K, V] private[concurrent] (
      val comparator: Comparator[_ >: K],
      var row: ConcurrentSkipListMap.Index[K, V] // the level to split out
      ,
      var current: ConcurrentSkipListMap.Node[
        K,
        V
      ] // current traversal node; initialize at origin
      ,
      val fence: K // exclusive upper bound for keys, or null if to end
      ,
      var est: Long // size estimate
  ) {
    final def estimateSize: Long = {
      return est
    }
  }

  final private[concurrent] class KeySpliterator[K, V] private[concurrent] (
      val comparator: Comparator[_ >: K],
      val row: ConcurrentSkipListMap.Index[K, V],
      val origin: ConcurrentSkipListMap.Node[K, V],
      val fence: K,
      val est: Long
  ) extends ConcurrentSkipListMap.CSLMSpliterator[K, V](
        comparator,
        row,
        origin,
        fence,
        est
      )
      with Spliterator[K] {

    override def trySplit: ConcurrentSkipListMap.KeySpliterator[K, V] = {
      var e: ConcurrentSkipListMap.Node[K, V] = null
      var ek: K = null.asInstanceOf[K]
      val cmp: Comparator[_ >: K] = comparator
      val f: K = fence
      if ({
        e = current;
        e != null && (ek = e.key) != null
      }) {
        var q: ConcurrentSkipListMap.Index[K, V] = row
        while ({
          q != null
        }) {
          var s: ConcurrentSkipListMap.Index[K, V] = null
          var b: ConcurrentSkipListMap.Node[K, V] = null
          var n: ConcurrentSkipListMap.Node[K, V] = null
          var sk: K = null.asInstanceOf[K]
          if ({
            s = q.right;
            b = s.node;
            n = b.next;
            sk = n.key;
            s != null && b != null && n != null && n.`val` != null && sk != null && ConcurrentSkipListMap.ConcurrentSkipListMap
              .cpr(
                cmp,
                sk,
                ek
              ) > 0 && (f == null || ConcurrentSkipListMap.ConcurrentSkipListMap
              .cpr(
                cmp,
                sk,
                f
              ) < 0)
          }) {
            current = n
            val r: ConcurrentSkipListMap.Index[K, V] = q.down
            row = if ((s.right != null)) {
              s
            } else {
              s.down
            }
            est = est - (est >>> 2);
            return new ConcurrentSkipListMap.KeySpliterator[K, V](
              cmp,
              r,
              e,
              sk,
              est
            )
          }

          q = q.down
          row = q.down
        }
      }
      return null
    }

    override def forEachRemaining(action: Consumer[_ >: K]): Unit = {
      if (action == null) {
        throw new NullPointerException
      }
      val cmp: Comparator[_ >: K] = comparator
      val f: K = fence
      var e: ConcurrentSkipListMap.Node[K, V] = current
      current = null

      var continue = true
      while (e != null && continue) {
        var k: K = null.asInstanceOf[K]
        if ({
          (k = e.key);
          k != null && f != null && ConcurrentSkipListMap.cpr(cmp, f, k) <= 0
        }) {
          continue = false
        }
        if (continue) {
          if (e.`val` != null) {
            action.accept(k)
          }
          e = e.next
        }
      }
    }

    override def tryAdvance(action: Consumer[_ >: K]): Boolean = {
      if (action == null) {
        throw new NullPointerException
      }
      val cmp: Comparator[_ >: K] = comparator
      val f: K = fence
      var e: ConcurrentSkipListMap.Node[K, V] = current

      while ({
        e != null
      }) {
        var k: K = null.asInstanceOf[K]
        if ((k = e.key) != null && f != null && ConcurrentSkipListMap.cpr(
              cmp,
              f,
              k
            ) <= 0) {
          e = null
          break // todo: break is not supported

        }
        if (e.`val` != null) {
          current = e.next
          action.accept(k)
          return true
        }

        e = e.next
      }
      current = e
      return false
    }

    override def characteristics: Int = {
      return Spliterator.DISTINCT | Spliterator.SORTED | Spliterator.ORDERED | Spliterator.CONCURRENT | Spliterator.NONNULL
    }

    override final def getComparator: Comparator[_ >: K] = {
      return comparator
    }
  }

  final private[concurrent] class ValueSpliterator[K, V] private[concurrent] (
      val comparator: Comparator[_ >: K],
      val row: ConcurrentSkipListMap.Index[K, V],
      val origin: ConcurrentSkipListMap.Node[K, V],
      val fence: K,
      val est: Long
  ) extends ConcurrentSkipListMap.CSLMSpliterator[K, V](
        comparator,
        row,
        origin,
        fence,
        est
      )
      with Spliterator[V] {
    override def trySplit: ConcurrentSkipListMap.ValueSpliterator[K, V] = {
      var e: ConcurrentSkipListMap.Node[K, V] = null
      var ek: K = null.asInstanceOf[K]
      val cmp: Comparator[_ >: K] = comparator
      val f: K = fence
      if ((e = current) != null && (ek = e.key) != null) {
        var q: ConcurrentSkipListMap.Index[K, V] = row
        while ({
          q != null
        }) {
          var s: ConcurrentSkipListMap.Index[K, V] = null
          var b: ConcurrentSkipListMap.Node[K, V] = null
          var n: ConcurrentSkipListMap.Node[K, V] = null
          var sk: K = null.asInstanceOf[K]
          if ((s = q.right) != null && (b = s.node) != null && (n =
                b.next) != null && n.`val` != null && (sk =
                n.key) != null && ConcurrentSkipListMap.cpr(
                cmp,
                sk,
                ek
              ) > 0 && (f == null || ConcurrentSkipListMap.cpr(
                cmp,
                sk,
                f
              ) < 0)) {
            current = n
            val r: ConcurrentSkipListMap.Index[K, V] = q.down
            row = if ((s.right != null)) {
              s
            } else {
              s.down
            }
            est = est - (est >>> 2)
            return new ConcurrentSkipListMap.ValueSpliterator[K, V](
              cmp,
              r,
              e,
              sk,
              est
            )
          }

          q = row = q.down
        }
      }
      return null
    }

    override def forEachRemaining(action: Consumer[_ >: V]): Unit = {
      if (action == null) {
        throw new NullPointerException
      }
      val cmp: Comparator[_ >: K] = comparator
      val f: K = fence
      var e: ConcurrentSkipListMap.Node[K, V] = current
      current = null

      while ({
        e != null
      }) {
        var k: K = null.asInstanceOf[K]
        var v: V = null.asInstanceOf[V]
        if ((k = e.key) != null && f != null && ConcurrentSkipListMap.cpr(
              cmp,
              f,
              k
            ) <= 0) {
          break // todo: break is not supported
        }
        if ((v = e.`val`) != null) {
          action.accept(v)
        }

        e = e.next
      }
    }

    override def tryAdvance(action: Consumer[_ >: V]): Boolean = {
      if (action == null) {
        throw new NullPointerException
      }
      val cmp: Comparator[_ >: K] = comparator
      val f: K = fence
      var e: ConcurrentSkipListMap.Node[K, V] = current

      while ({
        e != null
      }) {
        var k: K = null.asInstanceOf[K]
        var v: V = null.asInstanceOf[V]
        if ((k = e.key) != null && f != null && ConcurrentSkipListMap.cpr(
              cmp,
              f,
              k
            ) <= 0) {
          e = null
          break // todo: break is not supported

        }
        if ((v = e.`val`) != null) {
          current = e.next
          action.accept(v)
          return true
        }

        e = e.next
      }
      current = e
      return false
    }

    override def characteristics: Int = {
      return Spliterator.CONCURRENT | Spliterator.ORDERED | Spliterator.NONNULL
    }
  }

  final private[concurrent] class EntrySpliterator[K, V] private[concurrent] (
      val comparator: Comparator[_ >: K],
      val row: ConcurrentSkipListMap.Index[K, V],
      val origin: ConcurrentSkipListMap.Node[K, V],
      val fence: K,
      val est: Long
  ) extends ConcurrentSkipListMap.CSLMSpliterator[K, V](
        comparator,
        row,
        origin,
        fence,
        est
      )
      with Spliterator[Map.Entry[K, V]] {

    override def trySplit: ConcurrentSkipListMap.EntrySpliterator[K, V] = {
      var e: ConcurrentSkipListMap.Node[K, V] = null
      var ek: K = null.asInstanceOf[K]
      val cmp: Comparator[_ >: K] = comparator
      val f: K = fence
      if ({
        e = current;
        ek = e.key;
        e != null && ek != null
      }) {
        var q: ConcurrentSkipListMap.Index[K, V] = row
        while ({
          q != null
        }) {
          var s: ConcurrentSkipListMap.Index[K, V] = null
          var b: ConcurrentSkipListMap.Node[K, V] = null
          var n: ConcurrentSkipListMap.Node[K, V] = null
          var sk: K = null.asInstanceOf[K]
          if ((s = q.right) != null && (b = s.node) != null && (n =
                b.next) != null && n.`val` != null && (sk =
                n.key) != null && ConcurrentSkipListMap.cpr(
                cmp,
                sk,
                ek
              ) > 0 && (f == null || ConcurrentSkipListMap.cpr(
                cmp,
                sk,
                f
              ) < 0)) {
            current = n
            val r: ConcurrentSkipListMap.Index[K, V] = q.down
            row = if ((s.right != null)) {
              s
            } else {
              s.down
            }
            est = est - (est >>> 2)
            return new ConcurrentSkipListMap.EntrySpliterator[K, V](
              cmp,
              r,
              e,
              sk,
              est
            )
          }

          q = q.down
          row = q.down
        }
      }
      return null
    }

    override def forEachRemaining(
        action: Consumer[_ >: Map.Entry[K, V]]
    ): Unit = {
      if (action == null) {
        throw new NullPointerException
      }
      val cmp: Comparator[_ >: K] = comparator
      val f: K = fence
      var e: ConcurrentSkipListMap.Node[K, V] = current
      current = null

      while ({
        e != null
      }) {
        var k: K = null.asInstanceOf[K]
        var v: V = null.asInstanceOf[V]
        if ((k = e.key) != null && f != null && ConcurrentSkipListMap.cpr(
              cmp,
              f,
              k
            ) <= 0) {
          break // todo: break is not supported
        }
        if ((v = e.`val`) != null) {
          action.accept(new AbstractMap.SimpleImmutableEntry[K, V](k, v))
        }

        e = e.next
      }
    }

    override def tryAdvance(
        action: Consumer[_ >: Map.Entry[K, V]]
    ): Boolean = {
      if (action == null) {
        throw new NullPointerException
      }
      val cmp: Comparator[_ >: K] = comparator
      val f: K = fence
      var e: ConcurrentSkipListMap.Node[K, V] = current

      var continue = true
      while ({
        e != null && continue
      }) {
        var k: K = null.asInstanceOf[K]
        var v: V = null.asInstanceOf[V]
        if ({
          k = e.key;
          k != null && f != null && ConcurrentSkipListMap.cpr(cmp, f, k) <= 0
        }) {
          e = null
          continue = false
        }
        if (continue) {
          if ((v = e.`val`) != null) {
            current = e.next
            action.accept(new AbstractMap.SimpleImmutableEntry[K, V](k, v))
            return true
          }
          e = e.next
        }
      }
      current = e
      return false
    }

    override def characteristics: Int = {
      return Spliterator.DISTINCT | Spliterator.SORTED | Spliterator.ORDERED | Spliterator.CONCURRENT | Spliterator.NONNULL
    }

    override final def getComparator
        : Comparator[Map.Entry[K, V]] = { // Adapt or create a key-based comparator
      if (comparator != null) {
        return Map.Entry.comparingByKey(comparator)
      } else {
        return (e1: Map.Entry[K, V], e2: Map.Entry[K, V]) =>
          {
            def foo(e1: Map.Entry[K, V], e2: Map.Entry[K, V]) = {
              @SuppressWarnings(Array("unchecked")) val k1: Comparable[_ >: K] =
                e1.getKey.asInstanceOf[Comparable[_ >: K]]
              return k1.compareTo(e2.getKey)
            }

            foo(e1, e2)
          }.asInstanceOf[Comparator[Map.Entry[K, V]] with Serializable]
      }
    }
  }

  // VarHandle mechanics
  private var HEAD: VarHandle = null
  private var ADDER: VarHandle = null
  private var NEXT: VarHandle = null
  private var VAL: VarHandle = null
  private var RIGHT: VarHandle = null

  try
    try {
      val l: MethodHandles.Lookup = MethodHandles.lookup
      HEAD = l.findVarHandle(
        classOf[ConcurrentSkipListMap[_, _]],
        "head",
        classOf[ConcurrentSkipListMap.Index[_, _]]
      )
      ADDER = l.findVarHandle(
        classOf[ConcurrentSkipListMap[_, _]],
        "adder",
        classOf[LongAdder]
      )
      NEXT = l.findVarHandle(
        classOf[ConcurrentSkipListMap.Node[_, _]],
        "next",
        classOf[ConcurrentSkipListMap.Node[_, _]]
      )
      VAL = l.findVarHandle(
        classOf[ConcurrentSkipListMap.Node[_, _]],
        "val",
        classOf[Any]
      )
      RIGHT = l.findVarHandle(
        classOf[ConcurrentSkipListMap.Index[_, _]],
        "right",
        classOf[ConcurrentSkipListMap.Index[_, _]]
      )
    } catch {
      case e: ReflectiveOperationException =>
        throw new ExceptionInInitializerError(e)
    }

}

@SerialVersionUID(-8627078645895051609L)
class ConcurrentSkipListMap[K, V]()

/** Constructs a new, empty map, sorted according to the {@linkplain Comparable
 *  natural ordering} of the keys.
 */
    extends AbstractMap[K, V]
    with ConcurrentNavigableMap[K, V]
    with Cloneable
    with Serializable {
  this.comparator = null

  /** The comparator used to maintain order in this map, or null if using
   *  natural ordering. (Non-private to simplify access in nested classes.)
   *
   *  @serial
   */
  // TODO: c
  // @SuppressWarnings(Array("serial")) // Conditionally serializable final private[concurrent]  var comparator: Comparator[_ >: K] = null

  /** Lazily initialized topmost index of the skiplist. */
  private var head: ConcurrentSkipListMap.Index[K, V] = null

  /** Lazily initialized element count */
  private var adder: LongAdder = null

  /** Lazily initialized key set */
  private var keySet: ConcurrentSkipListMap.KeySet[K, V] = null

  /** Lazily initialized values collection */
  private var values: ConcurrentSkipListMap.Values[K, V] = null

  /** Lazily initialized entry set */
  private var entrySet: ConcurrentSkipListMap.EntrySet[K, V] = null

  /** Lazily initialized descending map */
  private var descendingMap: ConcurrentSkipListMap.SubMap[K, V] = null

  /** Returns the header for base node list, or null if uninitialized
   */
  final private[concurrent] def baseHead: ConcurrentSkipListMap.Node[K, V] = {
    var h: ConcurrentSkipListMap.Index[K, V] = null
    VarHandle.acquireFence()
    return if (((h = head) == null)) {
      null
    } else {
      h.node
    }
  }

  /** Adds to element count, initializing adder if necessary
   *
   *  @param c
   *    count to add
   */
  private def addCount(c: Long): Unit = {
    var a: LongAdder = null
    do {
      {}
    } while ({
      (a = adder) == null && !(ConcurrentSkipListMap.ADDER
        .compareAndSet(this, null, a = new LongAdder))
    })
    a.add(c)
  }

  /** Returns element count, initializing adder if necessary.
   */
  final private[concurrent] def getAdderCount: Long = {
    var a: LongAdder = null
    var c: Long = 0L
    do {
      a = adder
    } while ({
      a == null && !(ConcurrentSkipListMap.ADDER
        .compareAndSet(this, null, a = new LongAdder))
    })
    return if (((c = a.sum) <= 0L)) {
      0L
    } else {
      c
    } // ignore transient negatives

  }

  /** Returns an index node with key strictly less than given key. Also unlinks
   *  indexes to deleted nodes found along the way. Callers rely on this
   *  side-effect of clearing indices to deleted nodes.
   *
   *  @param key
   *    if nonnull the key
   *  @return
   *    a predecessor node of key, or null if uninitialized or null key
   */
  private def findPredecessor(
      key: Any,
      cmp: Comparator[_ >: K]
  ): ConcurrentSkipListMap.Node[K, V] = {
    var q: ConcurrentSkipListMap.Index[K, V] = null
    VarHandle.acquireFence()
    if ({ q = head; q == null || key == null }) {
      return null
    } else {
      var r: ConcurrentSkipListMap.Index[K, V] = null
      var d: ConcurrentSkipListMap.Index[K, V] = null
      var continue1 = true
      var continue2 = true
      while (continue1) {
        while ({ r = q.right; r != null && continue2 }) {
          var p: ConcurrentSkipListMap.Node[K, V] = null
          var k: K = null.asInstanceOf[K]
          if ({
            p = r.node;
            k = p.key;
            p == null || k == null || p.`val` == null
          }) { // unlink index to deleted node
            ConcurrentSkipListMap.RIGHT.compareAndSet(q, r, r.right)
          } else if (ConcurrentSkipListMap.cpr(cmp, key, k) > 0) {
            q = r
          } else {
            continue2 = false
          }
        }
        if ({ d = q.down; d != null }) {
          q = d
        } else {
          return q.node
        }
      }
      null
    }
  }

  /** Returns node holding key or null if no such, clearing out any deleted
   *  nodes seen along the way. Repeatedly traverses at base-level looking for
   *  key starting at predecessor returned from findPredecessor, processing
   *  base-level deletions as encountered. Restarts occur, at traversal step
   *  encountering node n, if n's key field is null, indicating it is a marker,
   *  so its predecessor is deleted before continuing, which we help do by
   *  re-finding a valid predecessor. The traversal loops in doPut, doRemove,
   *  and findNear all include the same checks.
   *
   *  @param key
   *    the key
   *  @return
   *    node holding key, or null if no such
   */
  private def findNode(key: Any): ConcurrentSkipListMap.Node[K, V] = {
    if (key == null) {
      throw new NullPointerException // don't postpone errors
    }
    val cmp: Comparator[_ >: K] = comparator
    var b: ConcurrentSkipListMap.Node[K, V] = null
    var continue1 = true
    var continue2 = true
    while ({
      b = findPredecessor(key, cmp);
      b != null && continue1
    }) {
      while (continue2) {
        var n: ConcurrentSkipListMap.Node[K, V] = null
        var k: K = null.asInstanceOf[K]
        var v: V = null.asInstanceOf[V]
        var c: Int = 0
        if ({ n == b.next; n == null }) {
          continue1 = false
          continue2 = false
        } else if ({ k = n.key; k == null }) {
          continue2 = false
        } else if ({ v = n.`val`; v == null }) {
          ConcurrentSkipListMap.unlinkNode(b, n)
        } else if ({ c = ConcurrentSkipListMap.cpr(cmp, key, k); c > 0 }) {
          b = n
        } else if (c == 0) {
          return n
        } else {
          continue1 = false
          continue2 = false
        }
      }
    }
    return null
  }

  /** Gets value for key. Same idea as findNode, except skips over deletions and
   *  markers, and returns first encountered value to avoid possibly
   *  inconsistent rereads.
   *
   *  @param key
   *    the key
   *  @return
   *    the value, or null if absent
   */
  private def doGet(key: Any): V = {
    var q: ConcurrentSkipListMap.Index[K, V] = null
    VarHandle.acquireFence()
    if (key == null) {
      throw new NullPointerException
    }
    val cmp: Comparator[_ >: K] = comparator
    var result: V = null.asInstanceOf[V]
    if ({ q = head; q != null }) {
      var r: ConcurrentSkipListMap.Index[K, V] = null
      var d: ConcurrentSkipListMap.Index[K, V] = null
      var continue1 = true
      var continue2 = true
      while ({
        continue1
      }) {
        while ({
          r = q.right;
          r != null && continue2
        }) {
          var p: ConcurrentSkipListMap.Node[K, V] = null
          var k: K = null.asInstanceOf[K]
          var v: V = null.asInstanceOf[V]
          var c: Int = 0
          if ({
            p = r.node; k = p.key; v = p.`val`;
            p == null || k == null || v == null
          }) {
            ConcurrentSkipListMap.RIGHT.compareAndSet(q, r, r.right)
          } else if ({
            c = ConcurrentSkipListMap.cpr(cmp, key, k); c > 0;
          }) {
            q = r
          } else if (c == 0) {
            result = v;
            continue1 = false
            continue2 = false
          } else {
            continue2 = false
          }
        }
        if ({ d = q.down; d != null }) {
          q = d
        } else {
          var b: ConcurrentSkipListMap.Node[K, V] = null
          var n: ConcurrentSkipListMap.Node[K, V] = null
          if ({ b = q.node; b != null }) {
            var continue3 = true
            while ({
              n = b.next; n != null && continue3
            }) {
              var v: V = null.asInstanceOf[V]
              var c: Int = 0
              val k: K = n.key
              if ({
                v = n.`val`;
                c = ConcurrentSkipListMap.cpr(cmp, key, k);
                v == null || k == null || c > 0
              }) {
                b = n
              } else {
                if (c == 0) {
                  result = v
                }
                continue3 = false
              }
            }
          }
          continue2 = false
        }
      }
    }
    return result
  }

  /** Main insertion method. Adds element if not present, or replaces value if
   *  present and onlyIfAbsent is false.
   *
   *  @param key
   *    the key
   *  @param value
   *    the value that must be associated with key
   *  @param onlyIfAbsent
   *    if should not insert if already present
   *  @return
   *    the old value, or null if newly inserted
   */
  private def doPut(key: K, value: V, onlyIfAbsent: Boolean): V = {
    if (key == null) {
      throw new NullPointerException
    }
    val cmp: Comparator[_ >: K] = comparator

    while ({
      true
    }) {
      var h: ConcurrentSkipListMap.Index[K, V] = null
      var b: ConcurrentSkipListMap.Node[K, V] = null
      VarHandle.acquireFence()
      var levels: Int = 0 // number of levels descended
      if ((h = head) == null) { // try to initialize
        val base: ConcurrentSkipListMap.Node[K, V] =
          new ConcurrentSkipListMap.Node[K, V](
            null.asInstanceOf[K],
            null.asInstanceOf[V],
            null
          )
        h = new ConcurrentSkipListMap.Index[K, V](base, null, null)
        b = if ((ConcurrentSkipListMap.HEAD.compareAndSet(this, null, h))) {
          base
        } else {
          null
        }
      } else {
        var q: ConcurrentSkipListMap.Index[K, V] = h
        var r: ConcurrentSkipListMap.Index[K, V] = null
        var d: ConcurrentSkipListMap.Index[K, V] = null
        while ({
          true
        }) { // count while descending
          while ({
            r = q.right; r != null
          }) {
            var p: ConcurrentSkipListMap.Node[K, V] = null
            var k: K = null.asInstanceOf[K]
            if ((p = r.node) == null || (k =
                  p.key) == null || p.`val` == null) {
              ConcurrentSkipListMap.RIGHT.compareAndSet(q, r, r.right)
            } else {
              if (ConcurrentSkipListMap.cpr(cmp, key, k) > 0) {
                q = r
              } else {
                break // todo: break is not supported
              }
            }
          }
          if ((d = q.down) != null) {
            levels += 1
            q = d
          } else {
            b = q.node
            break // todo: break is not supported

          }
        }
      }
      if (b != null) {
        var z: ConcurrentSkipListMap.Node[K, V] = null // new node, if inserted

        while ({
          true
        }) { // find insertion point
          var n: ConcurrentSkipListMap.Node[K, V] = null
          var p: ConcurrentSkipListMap.Node[K, V] = null
          var k: K = null.asInstanceOf[K]
          var v: V = null.asInstanceOf[V]
          var c: Int = 0
          if ((n = b.next) == null) {
            if (b.key == null) { // if empty, type check key now
              ConcurrentSkipListMap.cpr(cmp, key, key)
            }
            c = -(1)
          } else {
            if ((k = n.key) == null) {
              break // todo: break is not supported
              // can't append; restart
            } else {
              if ((v = n.`val`) == null) {
                ConcurrentSkipListMap.unlinkNode(b, n)
                c = 1
              } else {
                if ((c = ConcurrentSkipListMap.cpr(cmp, key, k)) > 0) {
                  b = n
                } else {
                  if (c == 0 && (onlyIfAbsent || ConcurrentSkipListMap.VAL
                        .compareAndSet(n, v, value))) {
                    return v
                  }
                }
              }
            }
          }
          if (c < 0 && ConcurrentSkipListMap.NEXT.compareAndSet(
                b,
                n,
                p = new ConcurrentSkipListMap.Node[K, V](key, value, n)
              )) {
            z = p
            break // todo: break is not supported

          }
        }
        if (z != null) {
          val lr: Int = ThreadLocalRandom.nextSecondarySeed
          if ((lr & 0x3) == 0) { // add indices with 1/4 prob
            val hr: Int = ThreadLocalRandom.nextSecondarySeed
            var rnd: Long = (hr.toLong << 32) | (lr.toLong & 0xffffffffL)
            var skips: Int = levels // levels to descend before add
            var x: ConcurrentSkipListMap.Index[K, V] = null

            while ({
              true
            }) { // create at most 62 indices
              x = new ConcurrentSkipListMap.Index[K, V](z, x, null)
              if (rnd >= 0L || {
                    skips -= 1; skips
                  } < 0) {
                break // todo: break is not supported
              } else {
                rnd <<= 1
              }
            }
            if (ConcurrentSkipListMap.addIndices(
                  h,
                  skips,
                  x,
                  cmp
                ) && skips < 0 && (head eq h)) { // try to add new level
              val hx: ConcurrentSkipListMap.Index[K, V] =
                new ConcurrentSkipListMap.Index[K, V](z, x, null)
              val nh: ConcurrentSkipListMap.Index[K, V] =
                new ConcurrentSkipListMap.Index[K, V](h.node, h, hx)
              ConcurrentSkipListMap.HEAD.compareAndSet(this, h, nh)
            }
            if (z.`val` == null) { // deleted while adding indices
              findPredecessor(key, cmp) // clean
            }
          }
          addCount(1L)
          return null.asInstanceOf[V]
        }
      }
    }
  }

  /** Main deletion method. Locates node, nulls value, appends a deletion
   *  marker, unlinks predecessor, removes associated index nodes, and possibly
   *  reduces head index level.
   *
   *  @param key
   *    the key
   *  @param value
   *    if non-null, the value that must be associated with key
   *  @return
   *    the node, or null if not found
   */
  final private[concurrent] def doRemove(key: Any, value: Any): V = {
    if (key == null) {
      throw new NullPointerException
    }
    val cmp: Comparator[_ >: K] = comparator
    var result: V = null.asInstanceOf[V]
    var b: ConcurrentSkipListMap.Node[K, V] = null
    outer // todo: labels are not supported
    while ({
      (b = findPredecessor(key, cmp)) != null && result == null
    }) {
      while ({
        true
      }) {
        var n: ConcurrentSkipListMap.Node[K, V] = null
        var k: K = null.asInstanceOf[K]
        var v: V = null.asInstanceOf[V]
        var c: Int = 0
        if ((n = b.next) == null) {
          break outer // todo: label break is not supported
        } else {
          if ((k = n.key) == null) {
            break // todo: break is not supported
          } else {
            if ((v = n.`val`) == null) {
              ConcurrentSkipListMap.unlinkNode(b, n)
            } else {
              if ((c = ConcurrentSkipListMap.cpr(cmp, key, k)) > 0) {
                b = n
              } else {
                if (c < 0) {
                  break outer // todo: label break is not supported
                } else {
                  if (value != null && !(value == v)) {
                    break outer // todo: label break is not supported
                  } else {
                    if (ConcurrentSkipListMap.VAL.compareAndSet(n, v, null)) {
                      result = v
                      ConcurrentSkipListMap.unlinkNode(b, n)
                      break // todo: break is not supported
                      // loop to clean up

                    }
                  }
                }
              }
            }
          }
        }
      }
    }
    if (result != null) {
      tryReduceLevel()
      addCount(-(1L))
    }
    return result
  }

  /** Possibly reduce head level if it has no nodes. This method can (rarely)
   *  make mistakes, in which case levels can disappear even though they are
   *  about to contain index nodes. This impacts performance, not correctness.
   *  To minimize mistakes as well as to reduce hysteresis, the level is reduced
   *  by one only if the topmost three levels look empty. Also, if the removed
   *  level looks non-empty after CAS, we try to change it back quick before
   *  anyone notices our mistake! (This trick works pretty well because this
   *  method will practically never make mistakes unless current thread stalls
   *  immediately before first CAS, in which case it is very unlikely to stall
   *  again immediately afterwards, so will recover.)
   *
   *  We put up with all this rather than just let levels grow because
   *  otherwise, even a small map that has undergone a large number of
   *  insertions and removals will have a lot of levels, slowing down access
   *  more than would an occasional unwanted reduction.
   */
  private def tryReduceLevel(): Unit = {
    var h: ConcurrentSkipListMap.Index[K, V] = null
    var d: ConcurrentSkipListMap.Index[K, V] = null
    var e: ConcurrentSkipListMap.Index[K, V] = null
    if ((h = head) != null && h.right == null && (d =
          h.down) != null && d.right == null && (e =
          d.down) != null && e.right == null && ConcurrentSkipListMap.HEAD
          .compareAndSet(this, h, d) && h.right != null) { // recheck
      ConcurrentSkipListMap.HEAD.compareAndSet(this, d, h) // try to backout
    }
  }

  /** Gets first valid node, unlinking deleted nodes if encountered.
   *
   *  @return
   *    first node or null if empty
   */
  final private[concurrent] def findFirst: ConcurrentSkipListMap.Node[K, V] = {
    var b: ConcurrentSkipListMap.Node[K, V] = null
    var n: ConcurrentSkipListMap.Node[K, V] = null
    if ((b = baseHead) != null) {
      while ({
        (n = b.next) != null
      }) {
        if (n.`val` == null) {
          ConcurrentSkipListMap.unlinkNode(b, n)
        } else {
          return n
        }
      }
    }
    return null
  }

  /** Entry snapshot version of findFirst
   */
  final private[concurrent] def findFirstEntry
      : AbstractMap.SimpleImmutableEntry[K, V] = {
    var b: ConcurrentSkipListMap.Node[K, V] = null
    var n: ConcurrentSkipListMap.Node[K, V] = null
    var v: V = null
    if ((b = baseHead) != null) {
      while ({
        (n = b.next) != null
      }) {
        if ((v = n.`val`) == null) {
          ConcurrentSkipListMap.unlinkNode(b, n)
        } else {
          return new AbstractMap.SimpleImmutableEntry[K, V](n.key, v)
        }
      }
    }
    return null
  }

  /** Removes first entry; returns its snapshot.
   *
   *  @return
   *    null if empty, else snapshot of first entry
   */
  private def doRemoveFirstEntry: AbstractMap.SimpleImmutableEntry[K, V] = {
    var b: ConcurrentSkipListMap.Node[K, V] = null
    var n: ConcurrentSkipListMap.Node[K, V] = null
    var v: V = null
    if ((b = baseHead) != null) {
      while ({
        (n = b.next) != null
      }) {
        if ((v = n.`val`) == null || ConcurrentSkipListMap.VAL.compareAndSet(
              n,
              v,
              null
            )) {
          val k: K = n.key
          ConcurrentSkipListMap.unlinkNode(b, n)
          if (v != null) {
            tryReduceLevel()
            findPredecessor(k, comparator) // clean index

            addCount(-(1L))
            return new AbstractMap.SimpleImmutableEntry[K, V](k, v)
          }
        }
      }
    }
    return null
  }

  /** Specialized version of find to get last valid node.
   *
   *  @return
   *    last node or null if empty
   */
  final private[concurrent] def findLast: ConcurrentSkipListMap.Node[K, V] = {
    outer // todo: labels are not supported

    while ({
      true
    }) {
      var q: ConcurrentSkipListMap.Index[K, V] = null
      var b: ConcurrentSkipListMap.Node[K, V] = null
      VarHandle.acquireFence()
      if ((q = head) == null) {
        break // todo: break is not supported
      }
      var r: ConcurrentSkipListMap.Index[K, V] = null
      var d: ConcurrentSkipListMap.Index[K, V] = null
      while ({
        true
      }) {
        while ({
          (r = q.right) != null
        }) {
          var p: ConcurrentSkipListMap.Node[K, V] = null
          if ((p = r.node) == null || p.`val` == null) {
            ConcurrentSkipListMap.RIGHT.compareAndSet(q, r, r.right)
          } else {
            q = r
          }
        }
        if ((d = q.down) != null) {
          q = d
        } else {
          b = q.node
          break // todo: break is not supported

        }
      }
      if (b != null) {
        while ({
          true
        }) {
          var n: ConcurrentSkipListMap.Node[K, V] = null
          if ((n = b.next) == null) {
            if (b.key == null) {
              break outer // todo: label break is not supported
            } else {
              return b
            }
          } else {
            if (n.key == null) {
              break // todo: break is not supported
            } else {
              if (n.`val` == null) {
                ConcurrentSkipListMap.unlinkNode(b, n)
              } else {
                b = n
              }
            }
          }
        }
      }
    }
    return null
  }

  /** Entry version of findLast
   *
   *  @return
   *    Entry for last node or null if empty
   */
  final private[concurrent] def findLastEntry
      : AbstractMap.SimpleImmutableEntry[K, V] = {
    while ({
      true
    }) {
      var n: ConcurrentSkipListMap.Node[K, V] = null
      var v: V = null
      if ((n = findLast) == null) {
        return null
      }
      if ((v = n.`val`) != null) {
        return new AbstractMap.SimpleImmutableEntry[K, V](n.key, v)
      }
    }
  }

  /** Removes last entry; returns its snapshot. Specialized variant of doRemove.
   *
   *  @return
   *    null if empty, else snapshot of last entry
   */
  private def doRemoveLastEntry: Map.Entry[K, V] = {
    outer // todo: labels are not supported

    while ({
      true
    }) {
      var q: ConcurrentSkipListMap.Index[K, V] = null
      var b: ConcurrentSkipListMap.Node[K, V] = null
      VarHandle.acquireFence()
      if ((q = head) == null) {
        break // todo: break is not supported
      }

      while ({
        true
      }) {
        var d: ConcurrentSkipListMap.Index[K, V] = null
        var r: ConcurrentSkipListMap.Index[K, V] = null
        var p: ConcurrentSkipListMap.Node[K, V] = null
        while ({
          (r = q.right) != null
        }) {
          if ((p = r.node) == null || p.`val` == null) {
            ConcurrentSkipListMap.RIGHT.compareAndSet(q, r, r.right)
          } else {
            if (p.next != null) {
              q = r // continue only if a successor
            } else {
              break // todo: break is not supported
            }
          }
        }
        if ((d = q.down) != null) {
          q = d
        } else {
          b = q.node
          break // todo: break is not supported

        }
      }
      if (b != null) {
        while ({
          true
        }) {
          var n: ConcurrentSkipListMap.Node[K, V] = null
          var k: K = null
          var v: V = null
          if ((n = b.next) == null) {
            if (b.key == null) {
              break outer // todo: label break is not supported
            } else {
              break // todo: break is not supported
              // retry
            }
          } else {
            if ((k = n.key) == null) {
              break // todo: break is not supported
            } else {
              if ((v = n.`val`) == null) {
                ConcurrentSkipListMap.unlinkNode(b, n)
              } else {
                if (n.next != null) {
                  b = n
                } else {
                  if (ConcurrentSkipListMap.VAL.compareAndSet(n, v, null)) {
                    ConcurrentSkipListMap.unlinkNode(b, n)
                    tryReduceLevel()
                    findPredecessor(k, comparator)
                    addCount(-(1L))
                    return new AbstractMap.SimpleImmutableEntry[K, V](k, v)
                  }
                }
              }
            }
          }
        }
      }
    }
    return null
  }

  /** Utility for ceiling, floor, lower, higher methods.
   *
   *  @param key
   *    the key
   *  @param rel
   *    the relation -- OR'ed combination of EQ, LT, GT
   *  @return
   *    nearest node fitting relation, or null if no such
   */
  final private[concurrent] def findNear(
      key: K,
      rel: Int,
      cmp: Comparator[_ >: K]
  ): ConcurrentSkipListMap.Node[K, V] = {
    if (key == null) {
      throw new NullPointerException
    }
    var result: ConcurrentSkipListMap.Node[K, V] = null
    outer // todo: labels are not supported
    var b: ConcurrentSkipListMap.Node[K, V] = null
    while ({
      true
    }) {
      if ((b = findPredecessor(key, cmp)) == null) {
        result = null
        break // todo: break is not supported

      }

      while ({
        true
      }) {
        var n: ConcurrentSkipListMap.Node[K, V] = null
        var k: K = null
        var c: Int = 0
        if ((n = b.next) == null) {
          result =
            if (((rel & ConcurrentSkipListMap.LT) != 0 && b.key != null)) {
              b
            } else {
              null
            }
          break outer // todo: label break is not supported

        } else {
          if ((k = n.key) == null) {
            break // todo: break is not supported
          } else {
            if (n.`val` == null) {
              ConcurrentSkipListMap.unlinkNode(b, n)
            } else {
              if (((c = ConcurrentSkipListMap.cpr(
                    cmp,
                    key,
                    k
                  )) == 0 && (rel & ConcurrentSkipListMap.EQ) != 0) || (c < 0 && (rel & ConcurrentSkipListMap.LT) == 0)) {
                result = n
                break outer // todo: label break is not supported

              } else {
                if (c <= 0 && (rel & ConcurrentSkipListMap.LT) != 0) {
                  result = if ((b.key != null)) {
                    b
                  } else {
                    null
                  }
                  break outer // todo: label break is not supported

                } else {
                  b = n
                }
              }
            }
          }
        }
      }
    }
    return result
  }

  /** Variant of findNear returning SimpleImmutableEntry
   *
   *  @param key
   *    the key
   *  @param rel
   *    the relation -- OR'ed combination of EQ, LT, GT
   *  @return
   *    Entry fitting relation, or null if no such
   */
  final private[concurrent] def findNearEntry(
      key: K,
      rel: Int,
      cmp: Comparator[_ >: K]
  ): AbstractMap.SimpleImmutableEntry[K, V] = {
    while ({
      true
    }) {
      var n: ConcurrentSkipListMap.Node[K, V] = null
      var v: V = null
      if ((n = findNear(key, rel, cmp)) == null) {
        return null
      }
      if ((v = n.`val`) != null) {
        return new AbstractMap.SimpleImmutableEntry[K, V](n.key, v)
      }
    }
  }

  /** Constructs a new, empty map, sorted according to the specified comparator.
   *
   *  @param comparator
   *    the comparator that will be used to order this map. If {@code null}, the
   *    {@linkplain Comparable natural ordering} of the keys will be used.
   */
  def this(comparator: Comparator[_ >: K]) = {
    this()
    this.comparator = comparator
  }

  /** Constructs a new map containing the same mappings as the given map, sorted
   *  according to the {@linkplain Comparable natural ordering} of the keys.
   *
   *  @param m
   *    the map whose mappings are to be placed in this map
   *  @throws ClassCastException
   *    if the keys in {@code m} are not {@link Comparable}, or are not mutually
   *    comparable
   *  @throws NullPointerException
   *    if the specified map or any of its keys or values are null
   */
  def this(m: Map[_ <: K, _ <: V]) = {
    this()
    this.comparator = null
    putAll(m)
  }

  /** Constructs a new map containing the same mappings and using the same
   *  ordering as the specified sorted map.
   *
   *  @param m
   *    the sorted map whose mappings are to be placed in this map, and whose
   *    comparator is to be used to sort this map
   *  @throws NullPointerException
   *    if the specified sorted map or any of its keys or values are null
   */
  def this(m: SortedMap[K, _ <: V]) = {
    this()
    this.comparator = m.comparator
    buildFromSorted(m) // initializes transients

  }

  /** Returns a shallow copy of this {@code ConcurrentSkipListMap} instance.
   *  (The keys and values themselves are not cloned.)
   *
   *  @return
   *    a shallow copy of this map
   */
  override def clone: ConcurrentSkipListMap[K, V] = {
    try {
      @SuppressWarnings(Array("unchecked")) val clone
          : ConcurrentSkipListMap[K, V] =
        super.clone.asInstanceOf[ConcurrentSkipListMap[K, V]]
      clone.keySet = null
      clone.entrySet = null
      clone.values = null
      clone.descendingMap = null
      clone.adder = null
      clone.buildFromSorted(this)
      return clone
    } catch {
      case e: CloneNotSupportedException =>
        throw new InternalError
    }
  }

  /** Streamlined bulk insertion to initialize from elements of given sorted
   *  map. Call only from constructor or clone method.
   */
  private def buildFromSorted(map: SortedMap[K, _ <: V]): Unit = {
    if (map == null) {
      throw new NullPointerException
    }
    val it: Iterator[_ <: Map.Entry[_ <: K, _ <: V]] =
      map.entrySet.iterator
    /*
     * Add equally spaced indices at log intervals, using the bits
     * of count during insertion. The maximum possible resulting
     * level is less than the number of bits in a long (64). The
     * preds array tracks the current rightmost node at each
     * level.
     */ @SuppressWarnings(Array("unchecked")) val preds
        : Array[ConcurrentSkipListMap.Index[K, V]] =
      new Array[ConcurrentSkipListMap.Index[_, _]](64)
        .asInstanceOf[Array[ConcurrentSkipListMap.Index[K, V]]]
    var bp: ConcurrentSkipListMap.Node[K, V] =
      new ConcurrentSkipListMap.Node[K, V](null, null, null)
    var h: ConcurrentSkipListMap.Index[K, V] = preds(0) =
      new ConcurrentSkipListMap.Index[K, V](bp, null, null)
    var count: Long = 0
    while ({
      it.hasNext
    }) {
      val e: Map.Entry[_ <: K, _ <: V] = it.next
      val k: K = e.getKey
      val v: V = e.getValue
      if (k == null || v == null) {
        throw new NullPointerException
      }
      val z: ConcurrentSkipListMap.Node[K, V] =
        new ConcurrentSkipListMap.Node[K, V](k, v, null)
      bp = bp.next = z
      if (({
            count += 1; count
          } & 3L) == 0L) {
        var m: Long = count >>> 2
        var i: Int = 0
        var idx: ConcurrentSkipListMap.Index[K, V] = null
        var q: ConcurrentSkipListMap.Index[K, V] = null
        do {
          {
            idx = new ConcurrentSkipListMap.Index[K, V](z, idx, null)
            if ((q = preds(i)) == null) {
              preds(i) = h =
                new ConcurrentSkipListMap.Index[K, V](h.node, h, idx)
            } else {
              preds(i) = q.right = idx
            }
          }
        } while ({
          {
            i += 1; i
          } < preds.length && ((m >>>= 1) & 1L) != 0L
        })
      }
    }
    if (count != 0L) {
      VarHandle.releaseFence() // emulate volatile stores

      addCount(count)
      head = h
      VarHandle.fullFence()
    }
  }

  /** Saves this map to a stream (that is, serializes it).
   *
   *  @param s
   *    the stream
   *  @throws java.io.IOException
   *    if an I/O error occurs
   *  @serialData
   *    The key (Object) and value (Object) for each key-value mapping
   *    represented by the map, followed by {@code null}. The key-value mappings
   *    are emitted in key-order (as determined by the Comparator, or by the
   *    keys' natural ordering if no Comparator).
   */
  @throws[java.io.IOException]
  private def writeObject(s: ObjectOutputStream): Unit = { // Write out the Comparator and any hidden stuff
    s.defaultWriteObject()
    // Write out keys and values (alternating)
    var b: ConcurrentSkipListMap.Node[K, V] = null
    var n: ConcurrentSkipListMap.Node[K, V] = null
    var v: V = null
    if ((b = baseHead) != null) {
      while ({
        (n = b.next) != null
      }) {
        if ((v = n.`val`) != null) {
          s.writeObject(n.key)
          s.writeObject(v)
        }
        b = n
      }
    }
    s.writeObject(null)
  }

  /** Reconstitutes this map from a stream (that is, deserializes it).
   *
   *  @param s
   *    the stream
   *  @throws ClassNotFoundException
   *    if the class of a serialized object could not be found
   *  @throws java.io.IOException
   *    if an I/O error occurs
   */
  @SuppressWarnings(Array("unchecked"))
  @throws[java.io.IOException]
  @throws[ClassNotFoundException]
  private def readObject(s: ObjectInputStream): Unit = { // Read in the Comparator and any hidden stuff
    s.defaultReadObject()
    // Same idea as buildFromSorted
    @SuppressWarnings(Array("unchecked")) val preds
        : Array[ConcurrentSkipListMap.Index[K, V]] =
      new Array[ConcurrentSkipListMap.Index[_, _]](64)
        .asInstanceOf[Array[ConcurrentSkipListMap.Index[K, V]]]
    var bp: ConcurrentSkipListMap.Node[K, V] =
      new ConcurrentSkipListMap.Node[K, V](null, null, null)
    var h: ConcurrentSkipListMap.Index[K, V] = preds(0) =
      new ConcurrentSkipListMap.Index[K, V](bp, null, null)
    val cmp: Comparator[_ >: K] = comparator
    var prevKey: K = null
    var count: Long = 0

    while ({
      true
    }) {
      val k: K = s.readObject.asInstanceOf[K]
      if (k == null) {
        break // todo: break is not supported
      }
      val v: V = s.readObject.asInstanceOf[V]
      if (v == null) {
        throw new NullPointerException
      }
      if (prevKey != null && ConcurrentSkipListMap.cpr(cmp, prevKey, k) > 0) {
        throw new IllegalStateException("out of order")
      }
      prevKey = k
      val z: ConcurrentSkipListMap.Node[K, V] =
        new ConcurrentSkipListMap.Node[K, V](k, v, null)
      bp = bp.next = z
      if (({
            count += 1; count
          } & 3L) == 0L) {
        var m: Long = count >>> 2
        var i: Int = 0
        var idx: ConcurrentSkipListMap.Index[K, V] = null
        var q: ConcurrentSkipListMap.Index[K, V] = null
        do {
          {
            idx = new ConcurrentSkipListMap.Index[K, V](z, idx, null)
            if ((q = preds(i)) == null) {
              preds(i) = h =
                new ConcurrentSkipListMap.Index[K, V](h.node, h, idx)
            } else {
              preds(i) = q.right = idx
            }
          }
        } while ({
          {
            i += 1; i
          } < preds.length && ((m >>>= 1) & 1L) != 0L
        })
      }
    }
    if (count != 0L) {
      VarHandle.releaseFence()
      addCount(count)
      head = h
      VarHandle.fullFence()
    }
  }

  /** Returns {@code true} if this map contains a mapping for the specified key.
   *
   *  @param key
   *    key whose presence in this map is to be tested
   *  @return
   *    {@code true} if this map contains a mapping for the specified key
   *  @throws ClassCastException
   *    if the specified key cannot be compared with the keys currently in the
   *    map
   *  @throws NullPointerException
   *    if the specified key is null
   */
  override def containsKey(key: Any): Boolean = {
    return doGet(key) != null
  }

  /** Returns the value to which the specified key is mapped, or {@code null} if
   *  this map contains no mapping for the key.
   *
   *  <p>More formally, if this map contains a mapping from a key {@code k} to a
   *  value {@code v} such that {@code key} compares equal to {@code k}
   *  according to the map's ordering, then this method returns {@code v};
   *  otherwise it returns {@code null}. (There can be at most one such
   *  mapping.)
   *
   *  @throws ClassCastException
   *    if the specified key cannot be compared with the keys currently in the
   *    map
   *  @throws NullPointerException
   *    if the specified key is null
   */
  override def get(key: Any): V = {
    return doGet(key)
  }

  /** Returns the value to which the specified key is mapped, or the given
   *  defaultValue if this map contains no mapping for the key.
   *
   *  @param key
   *    the key
   *  @param defaultValue
   *    the value to return if this map contains no mapping for the given key
   *  @return
   *    the mapping for the key, if present; else the defaultValue
   *  @throws NullPointerException
   *    if the specified key is null
   *  @since 1.8
   */
  override def getOrDefault(key: Any, defaultValue: V): V = {
    var v: V = null
    return if ((v = doGet(key)) == null) {
      defaultValue
    } else {
      v
    }
  }

  /** Associates the specified value with the specified key in this map. If the
   *  map previously contained a mapping for the key, the old value is replaced.
   *
   *  @param key
   *    key with which the specified value is to be associated
   *  @param value
   *    value to be associated with the specified key
   *  @return
   *    the previous value associated with the specified key, or {@code null} if
   *    there was no mapping for the key
   *  @throws ClassCastException
   *    if the specified key cannot be compared with the keys currently in the
   *    map
   *  @throws NullPointerException
   *    if the specified key or value is null
   */
  override def put(key: K, value: V): V = {
    if (value == null) {
      throw new NullPointerException
    }
    return doPut(key, value, false)
  }

  /** Removes the mapping for the specified key from this map if present.
   *
   *  @param key
   *    key for which mapping should be removed
   *  @return
   *    the previous value associated with the specified key, or {@code null} if
   *    there was no mapping for the key
   *  @throws ClassCastException
   *    if the specified key cannot be compared with the keys currently in the
   *    map
   *  @throws NullPointerException
   *    if the specified key is null
   */
  override def remove(key: Any): V = {
    return doRemove(key, null)
  }

  /** Returns {@code true} if this map maps one or more keys to the specified
   *  value. This operation requires time linear in the map size. Additionally,
   *  it is possible for the map to change during execution of this method, in
   *  which case the returned result may be inaccurate.
   *
   *  @param value
   *    value whose presence in this map is to be tested
   *  @return
   *    {@code true} if a mapping to {@code value} exists; {@code false}
   *    otherwise
   *  @throws NullPointerException
   *    if the specified value is null
   */
  override def containsValue(value: Any): Boolean = {
    if (value == null) {
      throw new NullPointerException
    }
    var b: ConcurrentSkipListMap.Node[K, V] = null
    var n: ConcurrentSkipListMap.Node[K, V] = null
    var v: V = null
    if ((b = baseHead) != null) {
      while ({
        (n = b.next) != null
      }) {
        if ((v = n.`val`) != null && value == v) {
          return true
        } else {
          b = n
        }
      }
    }
    return false
  }

  /** {@inheritDoc }
   */
  override def size: Int = {
    var c: Long = 0L
    return (if ((baseHead == null)) {
              0
            } else {
              if (((c = getAdderCount) >= Integer.MAX_VALUE)) {
                Integer.MAX_VALUE
              } else {
                c.toInt
              }
            })
  }

  override def isEmpty: Boolean = {
    return findFirst == null
  }

  /** Removes all of the mappings from this map.
   */
  override def clear(): Unit = {
    var h: ConcurrentSkipListMap.Index[K, V] = null
    var r: ConcurrentSkipListMap.Index[K, V] = null
    var d: ConcurrentSkipListMap.Index[K, V] = null
    var b: ConcurrentSkipListMap.Node[K, V] = null
    VarHandle.acquireFence()
    while ({
      (h = head) != null
    }) {
      if ((r = h.right) != null) { // remove indices
        ConcurrentSkipListMap.RIGHT.compareAndSet(h, r, null)
      } else {
        if ((d = h.down) != null) { // remove levels
          ConcurrentSkipListMap.HEAD.compareAndSet(this, h, d)
        } else {
          var count: Long = 0L
          if ((b = h.node) != null) { // remove nodes
            var n: ConcurrentSkipListMap.Node[K, V] = null
            var v: V = null
            while ({
              (n = b.next) != null
            }) {
              if ((v = n.`val`) != null && ConcurrentSkipListMap.VAL
                    .compareAndSet(n, v, null)) {
                count -= 1
                v = null
              }
              if (v == null) {
                ConcurrentSkipListMap.unlinkNode(b, n)
              }
            }
          }
          if (count != 0L) {
            addCount(count)
          } else {
            break // todo: break is not supported
          }
        }
      }
    }
  }

  /** If the specified key is not already associated with a value, attempts to
   *  compute its value using the given mapping function and enters it into this
   *  map unless {@code null}. The function is <em>NOT</em> guaranteed to be
   *  applied once atomically only if the value is not present.
   *
   *  @param key
   *    key with which the specified value is to be associated
   *  @param mappingFunction
   *    the function to compute a value
   *  @return
   *    the current (existing or computed) value associated with the specified
   *    key, or null if the computed value is null
   *  @throws NullPointerException
   *    if the specified key is null or the mappingFunction is null
   *  @since 1.8
   */
  override def computeIfAbsent(
      key: K,
      mappingFunction: Function[_ >: K, _ <: V]
  ): V = {
    if (key == null || mappingFunction == null) {
      throw new NullPointerException
    }
    var v: V = null
    var p: V = null
    var r: V = null
    if ((v = doGet(key)) == null && (r = mappingFunction.apply(key)) != null) {
      v = if ((p = doPut(key, r, true)) == null) {
        r
      } else {
        p
      }
    }
    return v
  }

  /** If the value for the specified key is present, attempts to compute a new
   *  mapping given the key and its current mapped value. The function is
   *  <em>NOT</em> guaranteed to be applied once atomically.
   *
   *  @param key
   *    key with which a value may be associated
   *  @param remappingFunction
   *    the function to compute a value
   *  @return
   *    the new value associated with the specified key, or null if none
   *  @throws NullPointerException
   *    if the specified key is null or the remappingFunction is null
   *  @since 1.8
   */
  override def computeIfPresent(
      key: K,
      remappingFunction: BiFunction[_ >: K, _ >: V, _ <: V]
  ): V = {
    if (key == null || remappingFunction == null) {
      throw new NullPointerException
    }
    var n: ConcurrentSkipListMap.Node[K, V] = null
    var v: V = null
    while ({
      (n = findNode(key)) != null
    }) {
      if ((v = n.`val`) != null) {
        val r: V = remappingFunction.apply(key, v)
        if (r != null) {
          if (ConcurrentSkipListMap.VAL.compareAndSet(n, v, r)) {
            return r
          }
        } else {
          if (doRemove(key, v) != null) {
            break // todo: break is not supported
          }
        }
      }
    }
    return null
  }

  /** Attempts to compute a mapping for the specified key and its current mapped
   *  value (or {@code null} if there is no current mapping). The function is
   *  <em>NOT</em> guaranteed to be applied once atomically.
   *
   *  @param key
   *    key with which the specified value is to be associated
   *  @param remappingFunction
   *    the function to compute a value
   *  @return
   *    the new value associated with the specified key, or null if none
   *  @throws NullPointerException
   *    if the specified key is null or the remappingFunction is null
   *  @since 1.8
   */
  override def compute(
      key: K,
      remappingFunction: BiFunction[_ >: K, _ >: V, _ <: V]
  ): V = {
    if (key == null || remappingFunction == null) {
      throw new NullPointerException
    }

    while ({
      true
    }) {
      var n: ConcurrentSkipListMap.Node[K, V] = null
      var v: V = null
      var r: V = null
      if ((n = findNode(key)) == null) {
        if ((r = remappingFunction.apply(key, null)) == null) {
          break // todo: break is not supported
        }
        if (doPut(key, r, true) == null) {
          return r
        }
      } else {
        if ((v = n.`val`) != null) {
          if ((r = remappingFunction.apply(key, v)) != null) {
            if (ConcurrentSkipListMap.VAL.compareAndSet(n, v, r)) {
              return r
            }
          } else {
            if (doRemove(key, v) != null) {
              break // todo: break is not supported
            }
          }
        }
      }
    }
    return null
  }

  /** If the specified key is not already associated with a value, associates it
   *  with the given value. Otherwise, replaces the value with the results of
   *  the given remapping function, or removes if {@code null}. The function is
   *  <em>NOT</em> guaranteed to be applied once atomically.
   *
   *  @param key
   *    key with which the specified value is to be associated
   *  @param value
   *    the value to use if absent
   *  @param remappingFunction
   *    the function to recompute a value if present
   *  @return
   *    the new value associated with the specified key, or null if none
   *  @throws NullPointerException
   *    if the specified key or value is null or the remappingFunction is null
   *  @since 1.8
   */
  override def merge(
      key: K,
      value: V,
      remappingFunction: BiFunction[_ >: V, _ >: V, _ <: V]
  ): V = {
    if (key == null || value == null || remappingFunction == null) {
      throw new NullPointerException
    }

    while ({
      true
    }) {
      var n: ConcurrentSkipListMap.Node[K, V] = null
      var v: V = null
      var r: V = null
      if ((n = findNode(key)) == null) {
        if (doPut(key, value, true) == null) {
          return value
        }
      } else {
        if ((v = n.`val`) != null) {
          if ((r = remappingFunction.apply(v, value)) != null) {
            if (ConcurrentSkipListMap.VAL.compareAndSet(n, v, r)) {
              return r
            }
          } else {
            if (doRemove(key, v) != null) {
              return null
            }
          }
        }
      }
    }
  }

  /** Returns a {@link NavigableSet} view of the keys contained in this map.
   *
   *  <p>The set's iterator returns the keys in ascending order. The set's
   *  spliterator additionally reports {@link Spliterator# CONCURRENT}, {@link
   *  Spliterator# NONNULL}, {@link Spliterator# SORTED} and {@link Spliterator#
   *  ORDERED}, with an encounter order that is ascending key order.
   *
   *  <p>The {@linkplain Spliterator# getComparator ( ) spliterator's
   *  comparator} is {@code null} if the {@linkplain # comparator ( ) map's
   *  comparator} is {@code null}. Otherwise, the spliterator's comparator is
   *  the same as or imposes the same total ordering as the map's comparator.
   *
   *  <p>The set is backed by the map, so changes to the map are reflected in
   *  the set, and vice-versa. The set supports element removal, which removes
   *  the corresponding mapping from the map, via the {@code Iterator.remove},
   *  {@code Set.remove}, {@code removeAll}, {@code retainAll}, and {@code
   *  clear} operations. It does not support the {@code add} or {@code addAll}
   *  operations.
   *
   *  <p>The view's iterators and spliterators are <a
   *  href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
   *
   *  <p>This method is equivalent to method {@code navigableKeySet}.
   *
   *  @return
   *    a navigable set view of the keys in this map
   */
  override def keySet: NavigableSet[K] = {
    var ks: ConcurrentSkipListMap.KeySet[K, V] = null
    if ((ks = keySet) != null) {
      return ks
    }
    return keySet = new ConcurrentSkipListMap.KeySet[K, V](this)
  }

  override def navigableKeySet: NavigableSet[K] = {
    var ks: ConcurrentSkipListMap.KeySet[K, V] = null
    if ((ks = keySet) != null) {
      return ks
    }
    return keySet = new ConcurrentSkipListMap.KeySet[K, V](this)
  }

  /** Returns a {@link Collection} view of the values contained in this map.
   *  <p>The collection's iterator returns the values in ascending order of the
   *  corresponding keys. The collections's spliterator additionally reports
   *  {@link Spliterator# CONCURRENT}, {@link Spliterator# NONNULL} and {@link
   *  Spliterator# ORDERED}, with an encounter order that is ascending order of
   *  the corresponding keys.
   *
   *  <p>The collection is backed by the map, so changes to the map are
   *  reflected in the collection, and vice-versa. The collection supports
   *  element removal, which removes the corresponding mapping from the map, via
   *  the {@code Iterator.remove}, {@code Collection.remove}, {@code removeAll},
   *  {@code retainAll} and {@code clear} operations. It does not support the
   *  {@code add} or {@code addAll} operations.
   *
   *  <p>The view's iterators and spliterators are <a
   *  href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
   */
  override def values: Collection[V] = {
    var vs: ConcurrentSkipListMap.Values[K, V] = null
    if ((vs = values) != null) {
      return vs
    }
    return values = new ConcurrentSkipListMap.Values[K, V](this)
  }

  /** Returns a {@link Set} view of the mappings contained in this map.
   *
   *  <p>The set's iterator returns the entries in ascending key order. The
   *  set's spliterator additionally reports {@link Spliterator# CONCURRENT},
   *  {@link Spliterator# NONNULL}, {@link Spliterator# SORTED} and {@link
   *  Spliterator# ORDERED}, with an encounter order that is ascending key
   *  order.
   *
   *  <p>The set is backed by the map, so changes to the map are reflected in
   *  the set, and vice-versa. The set supports element removal, which removes
   *  the corresponding mapping from the map, via the {@code Iterator.remove},
   *  {@code Set.remove}, {@code removeAll}, {@code retainAll} and {@code clear}
   *  operations. It does not support the {@code add} or {@code addAll}
   *  operations.
   *
   *  <p>The view's iterators and spliterators are <a
   *  href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
   *
   *  <p>The {@code Map.Entry} elements traversed by the {@code iterator} or
   *  {@code spliterator} do <em>not</em> support the {@code setValue}
   *  operation.
   *
   *  @return
   *    a set view of the mappings contained in this map, sorted in ascending
   *    key order
   */
  override def entrySet: Set[Map.Entry[K, V]] = {
    var es: ConcurrentSkipListMap.EntrySet[K, V] = null
    if ((es = entrySet) != null) {
      return es
    }
    return entrySet = new ConcurrentSkipListMap.EntrySet[K, V](this)
  }

  override def descendingMap: ConcurrentNavigableMap[K, V] = {
    var dm: ConcurrentNavigableMap[K, V] = null
    if ((dm = descendingMap) != null) {
      return dm
    }
    return descendingMap = new ConcurrentSkipListMap.SubMap[K, V](
      this,
      null,
      false,
      null,
      false,
      true
    )
  }

  override def descendingKeySet: NavigableSet[K] = {
    return descendingMap.navigableKeySet
  }

  /** Compares the specified object with this map for equality. Returns {@code
   *  true} if the given object is also a map and the two maps represent the
   *  same mappings. More formally, two maps {@code m1} and {@code m2} represent
   *  the same mappings if {@code m1.entrySet().equals(m2.entrySet())}. This
   *  operation may return misleading results if either map is concurrently
   *  modified during execution of this method.
   *
   *  @param o
   *    object to be compared for equality with this map
   *  @return
   *    {@code true} if the specified object is equal to this map
   */
  override def equals(o: Any): Boolean = {
    if (o eq this) {
      return true
    }
    if (!((o.isInstanceOf[Map[_, _]]))) {
      return false
    }
    val m: Map[_, _] = o.asInstanceOf[Map[_, _]]
    try {
      val cmp: Comparator[_ >: K] = comparator
      // See JDK-8223553 for Iterator type wildcard rationale
      val it: Iterator[_ <: Map.Entry[_, _]] = m.entrySet.iterator
      if (m.isInstanceOf[SortedMap[_, _]] && ((m
            .asInstanceOf[SortedMap[_, _]])
            .comparator eq cmp)) {
        var b: ConcurrentSkipListMap.Node[K, V] = null
        var n: ConcurrentSkipListMap.Node[K, V] = null
        if ((b = baseHead) != null) {
          while ({
            (n = b.next) != null
          }) {
            var k: K = null
            var v: V = null
            if ((v = n.`val`) != null && (k = n.key) != null) {
              if (!(it.hasNext)) {
                return false
              }
              val e: Map.Entry[_, _] = it.next
              val mk: Any = e.getKey
              val mv: Any = e.getValue
              if (mk == null || mv == null) {
                return false
              }
              try
                if (ConcurrentSkipListMap.cpr(cmp, k, mk) != 0) {
                  return false
                }
              catch {
                case cce: ClassCastException =>
                  return false
              }
              if (!(mv == v)) {
                return false
              }
            }
            b = n
          }
        }
        return !(it.hasNext)
      } else {
        while ({
          it.hasNext
        }) {
          var v: V = null
          val e: Map.Entry[_, _] = it.next
          val mk: Any = e.getKey
          val mv: Any = e.getValue
          if (mk == null || mv == null || (v = get(mk)) == null || !(v == mv)) {
            return false
          }
        }
        var b: ConcurrentSkipListMap.Node[K, V] = null
        var n: ConcurrentSkipListMap.Node[K, V] = null
        if ((b = baseHead) != null) {
          var k: K = null
          var v: V = null
          var mv: Any = null
          while ({
            (n = b.next) != null
          }) {
            if ((v = n.`val`) != null && (k = n.key) != null && ((mv =
                  m.get(k)) == null || !(mv == v))) {
              return false
            }
            b = n
          }
        }
        return true
      }
    } catch {
      case unused @ (_: ClassCastException | _: NullPointerException) =>
        return false
    }
  }

  /** {@inheritDoc }
   *
   *  @return
   *    the previous value associated with the specified key, or {@code null} if
   *    there was no mapping for the key
   *  @throws ClassCastException
   *    if the specified key cannot be compared with the keys currently in the
   *    map
   *  @throws NullPointerException
   *    if the specified key or value is null
   */
  override def putIfAbsent(key: K, value: V): V = {
    if (value == null) {
      throw new NullPointerException
    }
    return doPut(key, value, true)
  }

  /** {@inheritDoc }
   *
   *  @throws ClassCastException
   *    if the specified key cannot be compared with the keys currently in the
   *    map
   *  @throws NullPointerException
   *    if the specified key is null
   */
  override def remove(key: Any, value: Any): Boolean = {
    if (key == null) {
      throw new NullPointerException
    }
    return value != null && doRemove(key, value) != null
  }

  /** {@inheritDoc }
   *
   *  @throws ClassCastException
   *    if the specified key cannot be compared with the keys currently in the
   *    map
   *  @throws NullPointerException
   *    if any of the arguments are null
   */
  override def replace(key: K, oldValue: V, newValue: V): Boolean = {
    if (key == null || oldValue == null || newValue == null) {
      throw new NullPointerException
    }

    while ({
      true
    }) {
      var n: ConcurrentSkipListMap.Node[K, V] = null
      var v: V = null
      if ((n = findNode(key)) == null) {
        return false
      }
      if ((v = n.`val`) != null) {
        if (!(oldValue == v)) {
          return false
        }
        if (ConcurrentSkipListMap.VAL.compareAndSet(n, v, newValue)) {
          return true
        }
      }
    }
  }

  override def replace(key: K, value: V): V = {
    if (key == null || value == null) {
      throw new NullPointerException
    }

    while ({
      true
    }) {
      var n: ConcurrentSkipListMap.Node[K, V] = null
      var v: V = null
      if ((n = findNode(key)) == null) {
        return null
      }
      if ((v = n.`val`) != null && ConcurrentSkipListMap.VAL.compareAndSet(
            n,
            v,
            value
          )) {
        return v
      }
    }
  }

  override def comparator: Comparator[_ >: K] = {
    return comparator
  }

  /** @throws NoSuchElementException
   *    {@inheritDoc }
   */
  override def firstKey: K = {
    val n: ConcurrentSkipListMap.Node[K, V] = findFirst
    if (n == null) {
      throw new NoSuchElementException
    }
    return n.key
  }

  override def lastKey: K = {
    val n: ConcurrentSkipListMap.Node[K, V] = findLast
    if (n == null) {
      throw new NoSuchElementException
    }
    return n.key
  }

  /** @throws ClassCastException
   *    {@inheritDoc }
   *  @throws NullPointerException
   *    if {@code fromKey} or {@code toKey} is null
   *  @throws IllegalArgumentException
   *    {@inheritDoc }
   */
  override def subMap(
      fromKey: K,
      fromInclusive: Boolean,
      toKey: K,
      toInclusive: Boolean
  ): ConcurrentNavigableMap[K, V] = {
    if (fromKey == null || toKey == null) {
      throw new NullPointerException
    }
    return new ConcurrentSkipListMap.SubMap[K, V](
      this,
      fromKey,
      fromInclusive,
      toKey,
      toInclusive,
      false
    )
  }

  /** @throws ClassCastException
   *    {@inheritDoc }
   *  @throws NullPointerException
   *    if {@code toKey} is null
   *  @throws IllegalArgumentException
   *    {@inheritDoc }
   */
  override def headMap(
      toKey: K,
      inclusive: Boolean
  ): ConcurrentNavigableMap[K, V] = {
    if (toKey == null) {
      throw new NullPointerException
    }
    return new ConcurrentSkipListMap.SubMap[K, V](
      this,
      null,
      false,
      toKey,
      inclusive,
      false
    )
  }

  /** @throws ClassCastException
   *    {@inheritDoc }
   *  @throws NullPointerException
   *    if {@code fromKey} is null
   *  @throws IllegalArgumentException
   *    {@inheritDoc }
   */
  override def tailMap(
      fromKey: K,
      inclusive: Boolean
  ): ConcurrentNavigableMap[K, V] = {
    if (fromKey == null) {
      throw new NullPointerException
    }
    return new ConcurrentSkipListMap.SubMap[K, V](
      this,
      fromKey,
      inclusive,
      null,
      false,
      false
    )
  }

  override def subMap(fromKey: K, toKey: K): ConcurrentNavigableMap[K, V] = {
    return subMap(fromKey, true, toKey, false)
  }

  override def headMap(toKey: K): ConcurrentNavigableMap[K, V] = {
    return headMap(toKey, false)
  }

  override def tailMap(fromKey: K): ConcurrentNavigableMap[K, V] = {
    return tailMap(fromKey, true)
  }

  /** Returns a key-value mapping associated with the greatest key strictly less
   *  than the given key, or {@code null} if there is no such key. The returned
   *  entry does <em>not</em> support the {@code Entry.setValue} method.
   *
   *  @throws ClassCastException
   *    {@inheritDoc }
   *  @throws NullPointerException
   *    if the specified key is null
   */
  override def lowerEntry(key: K): Map.Entry[K, V] = {
    return findNearEntry(key, ConcurrentSkipListMap.LT, comparator)
  }

  /** @throws ClassCastException
   *    {@inheritDoc }
   *  @throws NullPointerException
   *    if the specified key is null
   */
  override def lowerKey(key: K): K = {
    val n: ConcurrentSkipListMap.Node[K, V] =
      findNear(key, ConcurrentSkipListMap.LT, comparator)
    return if ((n == null)) {
      null
    } else {
      n.key
    }
  }

  /** Returns a key-value mapping associated with the greatest key less than or
   *  equal to the given key, or {@code null} if there is no such key. The
   *  returned entry does <em>not</em> support the {@code Entry.setValue}
   *  method.
   *
   *  @param key
   *    the key
   *  @throws ClassCastException
   *    {@inheritDoc }
   *  @throws NullPointerException
   *    if the specified key is null
   */
  override def floorEntry(key: K): Map.Entry[K, V] = {
    return findNearEntry(
      key,
      ConcurrentSkipListMap.LT | ConcurrentSkipListMap.EQ,
      comparator
    )
  }

  /** @param key
   *    the key
   *  @throws ClassCastException
   *    {@inheritDoc }
   *  @throws NullPointerException
   *    if the specified key is null
   */
  override def floorKey(key: K): K = {
    val n: ConcurrentSkipListMap.Node[K, V] = findNear(
      key,
      ConcurrentSkipListMap.LT | ConcurrentSkipListMap.EQ,
      comparator
    )
    return if ((n == null)) {
      null
    } else {
      n.key
    }
  }

  /** Returns a key-value mapping associated with the least key greater than or
   *  equal to the given key, or {@code null} if there is no such entry. The
   *  returned entry does <em>not</em> support the {@code Entry.setValue}
   *  method.
   *
   *  @throws ClassCastException
   *    {@inheritDoc }
   *  @throws NullPointerException
   *    if the specified key is null
   */
  override def ceilingEntry(key: K): Map.Entry[K, V] = {
    return findNearEntry(
      key,
      ConcurrentSkipListMap.GT | ConcurrentSkipListMap.EQ,
      comparator
    )
  }

  override def ceilingKey(key: K): K = {
    val n: ConcurrentSkipListMap.Node[K, V] = findNear(
      key,
      ConcurrentSkipListMap.GT | ConcurrentSkipListMap.EQ,
      comparator
    )
    return if ((n == null)) {
      null
    } else {
      n.key
    }
  }

  /** Returns a key-value mapping associated with the least key strictly greater
   *  than the given key, or {@code null} if there is no such key. The returned
   *  entry does <em>not</em> support the {@code Entry.setValue} method.
   *
   *  @param key
   *    the key
   *  @throws ClassCastException
   *    {@inheritDoc }
   *  @throws NullPointerException
   *    if the specified key is null
   */
  override def higherEntry(key: K): Map.Entry[K, V] = {
    return findNearEntry(key, ConcurrentSkipListMap.GT, comparator)
  }

  override def higherKey(key: K): K = {
    val n: ConcurrentSkipListMap.Node[K, V] =
      findNear(key, ConcurrentSkipListMap.GT, comparator)
    return if ((n == null)) {
      null
    } else {
      n.key
    }
  }

  /** Returns a key-value mapping associated with the least key in this map, or
   *  {@code null} if the map is empty. The returned entry does <em>not</em>
   *  support the {@code Entry.setValue} method.
   */
  override def firstEntry: Map.Entry[K, V] = {
    return findFirstEntry
  }

  /** Returns a key-value mapping associated with the greatest key in this map,
   *  or {@code null} if the map is empty. The returned entry does <em>not</em>
   *  support the {@code Entry.setValue} method.
   */
  override def lastEntry: Map.Entry[K, V] = {
    return findLastEntry
  }

  /** Removes and returns a key-value mapping associated with the least key in
   *  this map, or {@code null} if the map is empty. The returned entry does
   *  <em>not</em> support the {@code Entry.setValue} method.
   */
  override def pollFirstEntry: Map.Entry[K, V] = {
    return doRemoveFirstEntry
  }

  /** Removes and returns a key-value mapping associated with the greatest key
   *  in this map, or {@code null} if the map is empty. The returned entry does
   *  <em>not</em> support the {@code Entry.setValue} method.
   */
  override def pollLastEntry: Map.Entry[K, V] = {
    return doRemoveLastEntry
  }

  /** Base of iterator classes
   */
  abstract private[concurrent] class Iter[T] private[concurrent] ()

  /** Initializes ascending iterator for entire range. */
      extends Iterator[T] {
    advance(baseHead)
    private[concurrent] var lastReturned: ConcurrentSkipListMap.Node[K, V] =
      null
    private[concurrent] var next: ConcurrentSkipListMap.Node[K, V] = null
    private[concurrent] var nextValue: V = null

    override final def hasNext: Boolean = {
      return next != null
    }

    /** Advances next to higher entry. */
    final private[concurrent] def advance(
        b: ConcurrentSkipListMap.Node[K, V]
    ): Unit = {
      var n: ConcurrentSkipListMap.Node[K, V] = null
      var v: V = null
      if ((lastReturned = b) != null) {
        while ({
          (n = b.next) != null && (v = n.`val`) == null
        }) {
          b = n
        }
      }
      nextValue = v
      next = n
    }

    override final def remove(): Unit = {
      var n: ConcurrentSkipListMap.Node[K, V] = null
      var k: K = null
      if ((n = lastReturned) == null || (k = n.key) == null) {
        throw new IllegalStateException
      }
      // It would not be worth all of the overhead to directly
      // unlink from here. Using remove is fast enough.
      thisConcurrentSkipListMap.remove(k)
      lastReturned = null
    }
  }

  final private[concurrent] class ValueIterator extends Iter[V] {
    override def next: V = {
      var v: V = null
      if ((v = nextValue) == null) {
        throw new NoSuchElementException
      }
      advance(next)
      return v
    }
  }

  final private[concurrent] class KeyIterator extends Iter[K] {
    override def next: K = {
      var n: ConcurrentSkipListMap.Node[K, V] = null
      if ((n = next) == null) {
        throw new NoSuchElementException
      }
      val k: K = n.key
      advance(n)
      return k
    }
  }

  final private[concurrent] class EntryIterator extends Iter[Map.Entry[K, V]] {
    override def next: Map.Entry[K, V] = {
      var n: ConcurrentSkipListMap.Node[K, V] = null
      if ((n = next) == null) {
        throw new NoSuchElementException
      }
      val k: K = n.key
      val v: V = nextValue
      advance(n)
      return new AbstractMap.SimpleImmutableEntry[K, V](k, v)
    }
  }

  override def forEach(action: BiConsumer[_ >: K, _ >: V]): Unit = {
    if (action == null) {
      throw new NullPointerException
    }
    var b: ConcurrentSkipListMap.Node[K, V] = null
    var n: ConcurrentSkipListMap.Node[K, V] = null
    var v: V = null
    if ((b = baseHead) != null) {
      while ({
        (n = b.next) != null
      }) {
        if ((v = n.`val`) != null) {
          action.accept(n.key, v)
        }
        b = n
      }
    }
  }

  override def replaceAll(
      function: BiFunction[_ >: K, _ >: V, _ <: V]
  ): Unit = {
    if (function == null) {
      throw new NullPointerException
    }
    var b: ConcurrentSkipListMap.Node[K, V] = null
    var n: ConcurrentSkipListMap.Node[K, V] = null
    var v: V = null
    if ((b = baseHead) != null) {
      while ({
        (n = b.next) != null
      }) {
        while ({
          (v = n.`val`) != null
        }) {
          val r: V = function.apply(n.key, v)
          if (r == null) {
            throw new NullPointerException
          }
          if (ConcurrentSkipListMap.VAL.compareAndSet(n, v, r)) {
            break // todo: break is not supported
          }
        }
        b = n
      }
    }
  }

  /** Helper method for EntrySet.removeIf.
   */
  private[concurrent] def removeEntryIf(
      function: Predicate[_ >: Map.Entry[K, V]]
  ): Boolean = {
    if (function == null) {
      throw new NullPointerException
    }
    var removed: Boolean = false
    var b: ConcurrentSkipListMap.Node[K, V] = null
    var n: ConcurrentSkipListMap.Node[K, V] = null
    var v: V = null
    if ((b = baseHead) != null) {
      while ({
        (n = b.next) != null
      }) {
        if ((v = n.`val`) != null) {
          val k: K = n.key
          val e: Map.Entry[K, V] =
            new AbstractMap.SimpleImmutableEntry[K, V](k, v)
          if (function.test(e) && remove(k, v)) {
            removed = true
          }
        }
        b = n
      }
    }
    return removed
  }

  /** Helper method for Values.removeIf.
   */
  private[concurrent] def removeValueIf(
      function: Predicate[_ >: V]
  ): Boolean = {
    if (function == null) {
      throw new NullPointerException
    }
    var removed: Boolean = false
    var b: ConcurrentSkipListMap.Node[K, V] = null
    var n: ConcurrentSkipListMap.Node[K, V] = null
    var v: V = null
    if ((b = baseHead) != null) {
      while ({
        (n = b.next) != null
      }) {
        if ((v = n.`val`) != null && function.test(v) && remove(n.key, v)) {
          removed = true
        }
        b = n
      }
    }
    return removed
  }

  // factory method for KeySpliterator
  final private[concurrent] def keySpliterator
      : ConcurrentSkipListMap.KeySpliterator[K, V] = {
    var h: ConcurrentSkipListMap.Index[K, V] = null
    var n: ConcurrentSkipListMap.Node[K, V] = null
    var est: Long = 0L
    VarHandle.acquireFence()
    if ((h = head) == null) {
      n = null
      est = 0L
    } else {
      n = h.node
      est = getAdderCount
    }
    return new ConcurrentSkipListMap.KeySpliterator[K, V](
      comparator,
      h,
      n,
      null,
      est
    )
  }

  // Almost the same as keySpliterator()
  final private[concurrent] def valueSpliterator
      : ConcurrentSkipListMap.ValueSpliterator[K, V] = {
    var h: ConcurrentSkipListMap.Index[K, V] = null
    var n: ConcurrentSkipListMap.Node[K, V] = null
    var est: Long = 0L
    VarHandle.acquireFence()
    if ((h = head) == null) {
      n = null
      est = 0L
    } else {
      n = h.node
      est = getAdderCount
    }
    return new ConcurrentSkipListMap.ValueSpliterator[K, V](
      comparator,
      h,
      n,
      null,
      est
    )
  }

  final private[concurrent] def entrySpliterator
      : ConcurrentSkipListMap.EntrySpliterator[K, V] = {
    var h: ConcurrentSkipListMap.Index[K, V] = null
    var n: ConcurrentSkipListMap.Node[K, V] = null
    var est: Long = 0L
    VarHandle.acquireFence()
    if ((h = head) == null) {
      n = null
      est = 0L
    } else {
      n = h.node
      est = getAdderCount
    }
    return new ConcurrentSkipListMap.EntrySpliterator[K, V](
      comparator,
      h,
      n,
      null,
      est
    )
  }
}
