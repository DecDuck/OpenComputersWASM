package li.cil.oc.server.machine.wasm

import java.util

import li.cil.oc.api.machine.Value

import scala.collection.mutable

/**
 * Maps the integer handles seen by guests to host [[Value]]s (userdata).
 *
 * The same value object always maps to the same handle while it is in the
 * table, so repeatedly returning a value does not leak handles.
 */
class HandleTable {
  private val byId = mutable.HashMap.empty[Int, Value]
  private val byValue = new util.IdentityHashMap[Value, Integer]()
  private var nextId = 1

  def add(value: Value): Int = synchronized {
    val existing = byValue.get(value)
    if (existing != null) existing.intValue
    else {
      if (byId.size >= HandleTable.MaxHandles) {
        throw new IllegalStateException("too many open handles")
      }
      while (nextId == 0 || byId.contains(nextId)) nextId += 1
      val id = nextId
      nextId += 1
      byId(id) = value
      byValue.put(value, Int.box(id))
      id
    }
  }

  def get(id: Int): Option[Value] = synchronized(byId.get(id))

  def release(id: Int): Option[Value] = synchronized {
    byId.remove(id).map(value => {
      byValue.remove(value)
      value
    })
  }

  /** Removes all handles, returning the values that were referenced. */
  def clear(): Seq[Value] = synchronized {
    val values = byId.values.toSeq
    byId.clear()
    byValue.clear()
    nextId = 1
    values
  }

  /** All handles and their values, for persisting. */
  def entries: Seq[(Int, Value)] = synchronized(byId.toSeq.sortBy(_._1))

  /** Restores a handle previously listed by [[entries]]. */
  def restore(id: Int, value: Value): Unit = synchronized {
    byId(id) = value
    byValue.put(value, Int.box(id))
    nextId = nextId max (id + 1)
  }

  def size: Int = synchronized(byId.size)
}

object HandleTable {
  final val MaxHandles = 1 << 16
}

