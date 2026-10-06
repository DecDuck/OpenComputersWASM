package li.cil.oc.server.machine.wasm

import java.nio.charset.StandardCharsets

import li.cil.oc.OpenComputers
import li.cil.oc.Settings
import li.cil.oc.api

import scala.jdk.CollectionConverters._

/**
 * Loads the module a WebAssembly machine boots: the one stored on its EEPROM,
 * either as a plain WebAssembly binary or gzip-compressed.
 *
 * Like the Lua BIOS, the EEPROM decides what happens next. The default
 * "EEPROM (WASM BIOS)" loads `/init.wasm` from a file system and hands over to
 * it with `step::exec`; other EEPROMs (such as the WASM shell) are complete
 * programs.
 */
object WasmBootLoader {
  def load(machine: api.machine.Machine): Array[Byte] = {
    val components = machine.components.synchronized(machine.components.asScala.toSeq)
    val eeprom = components.collectFirst { case (address, "eeprom") => address }
      .getOrElse(throw new WasmGuestException("no EEPROM installed"))

    val code = firstBytes(tryInvoke(machine, eeprom, "get")).getOrElse(Array.empty[Byte])
    val module = ModuleFormat.decode(code, Settings.get.wasmMaxModuleSize)
    if (module == null) {
      throw new WasmGuestException("the EEPROM does not contain a WebAssembly program (try the EEPROM (WASM BIOS))")
    }
    module
  }

  private def tryInvoke(machine: api.machine.Machine, address: String, method: String, args: AnyRef*): Array[AnyRef] = {
    try Option(machine.invoke(address, method, args.toArray)).getOrElse(Array.empty)
    catch {
      case t: Throwable =>
        OpenComputers.log.debug("WebAssembly boot loader: {}.{} failed.", address, method, t)
        Array.empty
    }
  }

  private def firstBytes(results: Array[AnyRef]): Option[Array[Byte]] = Option(results).flatMap(_.headOption) match {
    case Some(bytes: Array[Byte]) => Some(bytes)
    case Some(text: String) => Some(text.getBytes(StandardCharsets.UTF_8))
    case _ => None
  }
}

