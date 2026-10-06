import java.util

import li.cil.oc.server.machine.wasm.HandleTable
import li.cil.oc.server.machine.wasm.WasmValues
import li.cil.oc.server.machine.wasm.abi.OcMachineWorld._
import li.cil.oc.server.machine.wasm.abi.VariantValue
import org.junit.runner.RunWith
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import scala.jdk.CollectionConverters._

@RunWith(classOf[JUnitRunner])
class WasmValuesTest extends AnyFunSpec with Matchers {
  private def nodes(tree: Array[AnyRef]): Seq[VariantValue] = tree(0).asInstanceOf[util.List[AnyRef]].asScala.map(_.asInstanceOf[VariantValue]).toSeq

  private def roots(tree: Array[AnyRef]): Seq[Int] = tree(1).asInstanceOf[util.List[AnyRef]].asScala.map(_.asInstanceOf[Integer].intValue).toSeq

  private def guestTree(rootNodes: VariantValue*): Array[AnyRef] =
    Array[AnyRef](util.List.of[AnyRef](rootNodes: _*), util.List.of[AnyRef](rootNodes.indices.map(Int.box): _*))

  describe("Host to guest conversion") {
    it("converts primitives the way Lua sees them") {
      val tree = WasmValues.toTree(Array[AnyRef](null, Boolean.box(true), Int.box(3), Long.box(4L), Double.box(1.5), "text", Array[Byte](1, 2), Char.box('x')), new HandleTable)
      nodes(tree) should be(Seq(
        VariantValue.of(NODE_NIL),
        VariantValue.of(NODE_BOOLEAN, Boolean.box(true)),
        VariantValue.of(NODE_INTEGER, Long.box(3)),
        VariantValue.of(NODE_INTEGER, Long.box(4)),
        VariantValue.of(NODE_NUMBER, Double.box(1.5)),
        VariantValue.of(NODE_TEXT, "text"),
        nodes(tree)(6),
        VariantValue.of(NODE_TEXT, "x")))
      nodes(tree)(6).payload.asInstanceOf[Array[Byte]] should be(Array[Byte](1, 2))
      roots(tree) should be(0 until 8)
    }

    it("converts collections to arrays and tables") {
      val tree = WasmValues.toTree(Array[AnyRef](Array[AnyRef]("a", "b"), Map("k" -> Int.box(1))), new HandleTable)
      val all = nodes(tree)
      all(roots(tree).head).index should be(NODE_ARRAY)
      all(roots(tree)(1)).index should be(NODE_TABLE)
      val pair = all(roots(tree)(1)).payload.asInstanceOf[util.List[AnyRef]].get(0).asInstanceOf[Array[AnyRef]]
      all(pair(0).asInstanceOf[Integer]) should be(VariantValue.of(NODE_TEXT, "k"))
      all(pair(1).asInstanceOf[Integer]) should be(VariantValue.of(NODE_INTEGER, Long.box(1)))
    }

    it("preserves cycles") {
      val map = new util.HashMap[AnyRef, AnyRef]()
      map.put("self", map)
      val tree = WasmValues.toTree(Array[AnyRef](map), new HandleTable)
      val table = nodes(tree)(roots(tree).head)
      val pair = table.payload.asInstanceOf[util.List[AnyRef]].get(0).asInstanceOf[Array[AnyRef]]
      pair(1) should be(Int.box(roots(tree).head))
    }
  }

  describe("Guest to host conversion") {
    it("converts arrays to one-based tables and keeps primitives") {
      val tree = guestTree(
        VariantValue.of(NODE_ARRAY, util.List.of[AnyRef](Int.box(1), Int.box(2))),
        VariantValue.of(NODE_TEXT, "a"),
        VariantValue.of(NODE_INTEGER, Long.box(7)))
      val values = WasmValues.fromTree(tree, new HandleTable)
      values(0) should be(util.Map.of(Long.box(1), "a", Long.box(2), Long.box(7)))
      values(1) should be("a")
      values(2) should be(Long.box(7))
    }

    it("skips nil table entries") {
      val tree = guestTree(
        VariantValue.of(NODE_TABLE, util.List.of[AnyRef](Array[AnyRef](Int.box(1), Int.box(1)), Array[AnyRef](Int.box(1), Int.box(2)), Array[AnyRef](Int.box(2), Int.box(1)))),
        VariantValue.of(NODE_TEXT, "k"),
        VariantValue.of(NODE_NIL))
      WasmValues.fromTree(tree, new HandleTable)(0) should be(util.Map.of("k", "k"))
    }

    it("rejects malformed trees") {
      an[IllegalArgumentException] should be thrownBy WasmValues.fromTree(
        Array[AnyRef](util.List.of(), util.List.of[AnyRef](Int.box(0))), new HandleTable)
      an[IllegalArgumentException] should be thrownBy WasmValues.fromTree(
        guestTree(VariantValue.of(NODE_OPAQUE, Int.box(1))), new HandleTable)
      val deep = new util.ArrayList[AnyRef]()
      for (i <- 0 to WasmValues.MaxDepth + 1) deep.add(VariantValue.of(NODE_ARRAY, util.List.of[AnyRef](Int.box(i + 1))))
      deep.add(VariantValue.of(NODE_NIL))
      an[IllegalArgumentException] should be thrownBy WasmValues.fromTree(Array[AnyRef](deep, util.List.of[AnyRef](Int.box(0))), new HandleTable)
    }
  }
}


