package li.cil.oc.server.machine.wasm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

import li.cil.oc.server.machine.wasm.abi.OcMachineWorld
import li.cil.oc.server.machine.wasm.abi.WitValueCodec
import net.minecraft.nbt.CompoundTag

/**
 * A call that has to run on the server thread. Kept as plain data, rather
 * than a closure, so machines can be persisted while one is pending.
 */
sealed trait PendingCall

object PendingCall {
  /** `component.invoke`; `args` is a `value-tree` as received from the guest. */
  final case class OnComponent(address: String, method: String, args: AnyRef) extends PendingCall

  /** `userdata.invoke`; `args` is a `value-tree` as received from the guest. */
  final case class OnValue(handle: Int, method: String, args: AnyRef) extends PendingCall

  def save(call: PendingCall): CompoundTag = {
    val nbt = new CompoundTag()
    call match {
      case OnComponent(address, method, args) =>
        nbt.putString("kind", "component")
        nbt.putString("address", address)
        nbt.putString("method", method)
        nbt.putByteArray("args", WitValueCodec.encode(OcMachineWorld.VALUE_TREE, args))
      case OnValue(handle, method, args) =>
        nbt.putString("kind", "value")
        nbt.putInt("handle", handle)
        nbt.putString("method", method)
        nbt.putByteArray("args", WitValueCodec.encode(OcMachineWorld.VALUE_TREE, args))
    }
    nbt
  }

  def load(nbt: CompoundTag): PendingCall = {
    val args = WitValueCodec.decode(OcMachineWorld.VALUE_TREE, nbt.getByteArray("args"))
    nbt.getString("kind") match {
      case "component" => OnComponent(nbt.getString("address"), nbt.getString("method"), args)
      case "value" => OnValue(nbt.getInt("handle"), nbt.getString("method"), args)
      case kind => throw new IllegalArgumentException("unknown pending call kind " + kind)
    }
  }
}

object Compression {
  def deflate(data: Array[Byte]): Array[Byte] = {
    val bytes = new ByteArrayOutputStream()
    val out = new DeflaterOutputStream(bytes, new Deflater(Deflater.BEST_COMPRESSION))
    try out.write(data) finally out.close()
    bytes.toByteArray
  }

  def inflate(data: Array[Byte]): Array[Byte] = {
    val in = new InflaterInputStream(new ByteArrayInputStream(data))
    try in.readAllBytes() finally in.close()
  }
}

