package li.cil.oc.server.machine.wasm

import java.util

import li.cil.oc.OpenComputers
import li.cil.oc.Settings
import li.cil.oc.api.machine.Value
import li.cil.oc.server.machine.wasm.abi.OcMachineWorld._
import li.cil.oc.server.machine.wasm.abi.VariantValue

import scala.jdk.CollectionConverters._

/**
 * Converts between the values exchanged with OpenComputers callbacks and the
 * `value-tree` WIT type.
 *
 * Guest to host conversion mirrors what the native Lua architectures pass to
 * callbacks: integers become `Long`, numbers `Double`, text `String`, bytes
 * `Array[Byte]`, and arrays and tables become `java.util.Map`s (arrays with
 * one-based `Long` keys).
 */
object WasmValues {
  /** Maximum nesting depth of tables/arrays, in either direction. */
  final val MaxDepth = 128

  // ----------------------------------------------------------------------- //

  /**
   * Builds a `value-tree` (as `Array(nodes, roots)`) from host values.
   * Shared and cyclic references are preserved.
   */
  def toTree(values: Array[AnyRef], handles: HandleTable): Array[AnyRef] = {
    val nodes = new util.ArrayList[AnyRef]()
    val memo = new util.IdentityHashMap[AnyRef, Integer]()

    def add(node: VariantValue): Int = {
      nodes.add(node)
      nodes.size - 1
    }

    def container(ref: AnyRef, kind: Int)(fill: => util.List[AnyRef]): Int = {
      val index = nodes.size
      nodes.add(null)
      memo.put(ref, Int.box(index))
      nodes.set(index, VariantValue.of(kind, fill))
      index
    }

    def array(ref: AnyRef, elements: Iterator[Any], depth: Int): Int = container(ref, NODE_ARRAY) {
      val list = new util.ArrayList[AnyRef]()
      elements.foreach(element => list.add(Int.box(convert(element, depth + 1))))
      list
    }

    def table(ref: AnyRef, entries: Iterator[(Any, Any)], depth: Int): Int = container(ref, NODE_TABLE) {
      val list = new util.ArrayList[AnyRef]()
      entries.foreach {
        case (null, _) =>
        case (key, value) => list.add(Array[AnyRef](Int.box(convert(key, depth + 1)), Int.box(convert(value, depth + 1))))
      }
      list
    }

    def convert(value: Any, depth: Int): Int = value match {
      case null | () | None => add(VariantValue.of(NODE_NIL))
      case _: scala.runtime.BoxedUnit => add(VariantValue.of(NODE_NIL))
      case v: java.lang.Boolean => add(VariantValue.of(NODE_BOOLEAN, v))
      case v@(_: java.lang.Byte | _: java.lang.Short | _: java.lang.Integer | _: java.lang.Long) =>
        add(VariantValue.of(NODE_INTEGER, Long.box(v.asInstanceOf[Number].longValue)))
      case v: java.lang.Character => add(VariantValue.of(NODE_TEXT, String.valueOf(v)))
      case v: java.lang.Number => add(VariantValue.of(NODE_NUMBER, Double.box(v.doubleValue)))
      case v: String => add(VariantValue.of(NODE_TEXT, v))
      case v: Array[Byte] => add(VariantValue.of(NODE_BYTES, v))
      case v: Value =>
        if (Settings.get.allowUserdata) add(VariantValue.of(NODE_OPAQUE, Int.box(handles.add(v))))
        else add(VariantValue.of(NODE_NIL))
      case _: AnyRef if depth >= MaxDepth => add(VariantValue.of(NODE_NIL))
      case ref: AnyRef if memo.containsKey(ref) => memo.get(ref).intValue
      case v: scala.collection.Map[_, _] => table(v, v.iterator.asInstanceOf[Iterator[(Any, Any)]], depth)
      case v: util.Map[_, _] => table(v, v.entrySet.iterator.asScala.map(e => (e.getKey, e.getValue)), depth)
      case v: Array[_] => array(v, v.iterator, depth)
      case v: scala.collection.Iterable[_] => array(v, v.iterator, depth)
      case v: Product with AnyRef => array(v, v.productIterator, depth)
      case v: java.lang.Iterable[_] => array(v, v.iterator.asScala, depth)
      case other =>
        OpenComputers.log.debug("Tried to pass an unsupported value of type {} to a WebAssembly machine.", other.getClass.getName)
        add(VariantValue.of(NODE_NIL))
    }

    val roots = new util.ArrayList[AnyRef]()
    if (values != null) {
      values.foreach(value => roots.add(Int.box(convert(value, 0))))
    }
    Array[AnyRef](nodes, roots)
  }

  // ----------------------------------------------------------------------- //

  /**
   * Converts a `value-tree` received from a guest into callback arguments.
   *
   * @throws IllegalArgumentException if the tree is malformed or references
   *                                  an unknown handle.
   */
  def fromTree(tree: AnyRef, handles: HandleTable): Array[AnyRef] = {
    val fields = tree.asInstanceOf[Array[AnyRef]]
    val nodes = fields(0).asInstanceOf[util.List[AnyRef]]
    val roots = fields(1).asInstanceOf[util.List[AnyRef]]
    val converted = new Array[AnyRef](nodes.size)
    val done = new Array[Boolean](nodes.size)

    def convert(index: Int, depth: Int): AnyRef = {
      if (index < 0 || index >= nodes.size) {
        throw new IllegalArgumentException("invalid value node index")
      }
      if (depth > MaxDepth) {
        throw new IllegalArgumentException("values nested too deeply")
      }
      if (done(index)) {
        return converted(index)
      }
      val node = nodes.get(index).asInstanceOf[VariantValue]
      node.index match {
        case NODE_NIL => null
        case NODE_BOOLEAN | NODE_INTEGER | NODE_NUMBER | NODE_TEXT | NODE_BYTES => node.payload
        case NODE_ARRAY =>
          val map = new util.HashMap[AnyRef, AnyRef]()
          converted(index) = map
          done(index) = true
          val elements = node.payload.asInstanceOf[util.List[AnyRef]]
          for (i <- 0 until elements.size) {
            val value = convert(elements.get(i).asInstanceOf[Integer].intValue, depth + 1)
            if (value != null) map.put(Long.box(i + 1), value)
          }
          map
        case NODE_TABLE =>
          val map = new util.HashMap[AnyRef, AnyRef]()
          converted(index) = map
          done(index) = true
          node.payload.asInstanceOf[util.List[AnyRef]].forEach(entry => {
            val pair = entry.asInstanceOf[Array[AnyRef]]
            val key = convert(pair(0).asInstanceOf[Integer].intValue, depth + 1)
            val value = convert(pair(1).asInstanceOf[Integer].intValue, depth + 1)
            if (key != null && value != null) map.put(key, value)
          })
          map
        case NODE_OPAQUE =>
          handles.get(node.payload.asInstanceOf[Integer].intValue).getOrElse(throw new IllegalArgumentException("invalid handle"))
        case _ => throw new IllegalArgumentException("invalid value node")
      }
    }

    val result = new Array[AnyRef](roots.size)
    for (i <- 0 until roots.size) {
      result(i) = convert(roots.get(i).asInstanceOf[Integer].intValue, 0)
    }
    result
  }
}


