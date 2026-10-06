import li.cil.oc.server.machine.wasm.Compression
import org.junit.runner.RunWith
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class WasmCompressionTest extends AnyFunSpec with Matchers {
  describe("Persisted module compression") {
    it("round trips") {
      val data = Array.tabulate[Byte](100000)(i => (i % 7).toByte)
      val compressed = Compression.deflate(data)
      compressed.length should be < data.length
      Compression.inflate(compressed) should be(data)
    }

    it("handles empty data") {
      Compression.inflate(Compression.deflate(Array.empty[Byte])) should be(Array.empty[Byte])
    }
  }
}

