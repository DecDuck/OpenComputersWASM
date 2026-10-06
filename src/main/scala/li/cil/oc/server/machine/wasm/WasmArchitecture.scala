package li.cil.oc.server.machine.wasm

import java.io.FileNotFoundException
import java.io.IOException
import java.util

import com.dylibso.chicory.runtime.TrapException
import com.dylibso.chicory.wasm.ChicoryException
import li.cil.oc.OpenComputers
import li.cil.oc.Settings
import li.cil.oc.api
import li.cil.oc.api.driver.item.Memory
import li.cil.oc.api.machine.Architecture
import li.cil.oc.api.machine.Callback
import li.cil.oc.api.machine.ExecutionResult
import li.cil.oc.api.machine.LimitReachedException
import li.cil.oc.api.machine.Value
import li.cil.oc.api.network.Component
import li.cil.oc.api.network.Connector
import li.cil.oc.common.SaveHandler
import li.cil.oc.server.driver.Registry
import li.cil.oc.server.machine.ArgumentsImpl
import li.cil.oc.server.machine.wasm.abi.AbiException
import li.cil.oc.server.machine.wasm.abi.OcMachineWorld._
import li.cil.oc.server.machine.wasm.abi.VariantValue
import li.cil.oc.server.machine.wasm.abi.WitValueCodec
import li.cil.oc.util.FontUtils
import net.minecraft.core.component.DataComponentHolder
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.common.MutableDataComponentHolder
import net.neoforged.neoforge.server.ServerLifecycleHooks

import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

/**
 * Runs WebAssembly modules implementing the `oc:machine` WIT world (see the
 * `wit/` directory in the repository).
 *
 * The guest is driven through `kernel.resume`, which is called once per
 * [[runThreaded]] and must return quickly. Callbacks that may not run on the
 * worker thread are scheduled for [[runSynchronized]] on the server thread:
 *  - plain guests receive `pending`, return `step::sync`, and get the result
 *    as `event::sync-result` on the next resume;
 *  - guests instrumented with Asyncify are suspended inside the import call
 *    instead, and continue from there once the result is available, so the
 *    call appears to block.
 *
 * Machines are persisted as a snapshot of the guest instance (linear memory,
 * globals and tables), the module itself, and host-side state such as
 * userdata handles and pending calls. Because the guest never runs while
 * the machine is saved, and a suspended guest's call stack lives in its
 * linear memory, this captures everything needed to continue.
 */
@Architecture.Name("WebAssembly")
class WasmArchitecture(val machine: api.machine.Machine) extends Architecture {
  private val budget = new ExecutionBudget()

  private val handles = new HandleTable()

  private var guest: WasmGuest = _

  /** The running module, kept to persist it alongside the snapshot. */
  private var code: Array[Byte] = _

  private var codeCompressed: Array[Byte] = _

  private var doneWithInitRun = false

  /** Call to perform in [[runSynchronized]], set while the guest waits for it. */
  private var pendingCall: PendingCall = _

  /** Result of the last synchronized call, passed to the guest next. */
  private var syncResult: VariantValue = _

  @volatile private var memoryBytes = 0L

  /** Captured stdout/stderr of the running guest (via WASI). */
  private var stdout: GuestOutput = _

  private var stderr: GuestOutput = _

  private var wasi: WasiSupport = _

  private def node = machine.node

  private def components = machine.components

  // ----------------------------------------------------------------------- //

  override def isInitialized: Boolean = doneWithInitRun

  override def recomputeMemory(components: java.lang.Iterable[ItemStack]): Boolean = {
    val installed = components.asScala.foldLeft(0.0)((acc, stack) => acc + (Option(api.Driver.driverFor(stack)) match {
      case Some(driver: Memory) => driver.amount(stack) * 1024
      case _ => 0.0
    }))
    memoryBytes = (installed * Settings.get.wasmMemoryScale).toLong max 0 min Settings.get.maxTotalRam
    memoryBytes > 0
  }

  private def maxPages: Int = (memoryBytes / WasmGuest.PAGE_SIZE).toInt

  // ----------------------------------------------------------------------- //

  override def initialize(): Boolean = {
    close()
    recomputeMemory(machine.host.internalComponents)
    true
  }

  override def close(): Unit = {
    handles.clear().foreach(dispose)
    guest = null
    code = null
    codeCompressed = null
    pendingCall = null
    syncResult = null
    doneWithInitRun = false
    if (wasi != null) {
      wasi.close()
      wasi = null
    }
    stdout = null
    stderr = null
  }

  /**
   * Links a module against the host API (and WASI), and either starts it
   * fresh or restores it from a snapshot.
   */
  private def link(module: Array[Byte], snapshot: GuestSnapshot): WasmGuest = {
    val bindings = createBindings()
    stdout = new GuestOutput(outputSink("stdout"))
    stderr = new GuestOutput(outputSink("stderr"))
    if (Settings.get.wasmEnableWasi) {
      wasi = new WasiSupport(stdout, stderr)
      bindings.provide(wasi.functions)
    }
    val instance =
      if (snapshot == null) WasmGuest.instantiate(module, maxPages, bindings, budget, Settings.get.wasmAsyncifyBufferSize)
      else WasmGuest.restore(module, maxPages, bindings, budget, snapshot)
    code = module
    codeCompressed = null
    instance
  }

  /**
   * Replaces the running module (e.g. a boot loader) with another one. The
   * old module's state and userdata handles are discarded.
   */
  private def replaceGuest(module: Array[Byte]): Unit = {
    handles.clear().foreach(dispose)
    pendingCall = null
    syncResult = null
    if (wasi != null) {
      wasi.close()
      wasi = null
    }
    guest = null
    guest = link(module, null)
  }

  private def outputSink(stream: String): java.util.function.Consumer[String] = {
    if (!Settings.get.wasmLogOutput) null
    else {
      val address = Option(node).map(_.address).getOrElse("?")
      line => OpenComputers.log.info("[WebAssembly {} {}] {}", address, stream, line)
    }
  }

  override def onConnect(): Unit = {}

  override def onSignal(): Unit = {}

  // ----------------------------------------------------------------------- //

  override def runSynchronized(): Unit = {
    val call = pendingCall
    pendingCall = null
    syncResult = if (call != null) execute(call) else callError(CALL_ERROR_FAILED, "no synchronized call pending")
  }

  override def runThreaded(isSynchronizedReturn: Boolean): ExecutionResult = {
    if (Settings.get.timeout > 0) {
      budget.arm((Settings.get.timeout * 1e9).toLong)
    }
    try {
      val step =
        if (isSynchronizedReturn) {
          val result = Option(syncResult).getOrElse(callError(CALL_ERROR_FAILED, "no synchronized call result"))
          syncResult = null
          if (guest.isSuspended) guest.continueSuspended(result)
          else guest.resume(VariantValue.of(EVENT_SYNC_RESULT, result))
        }
        else if (guest == null) {
          // The boot module uses direct callbacks to load the OS, so this
          // has to happen in the initialization run, where call budgets are
          // not enforced.
          guest = link(WasmBootLoader.load(machine), null)
          guest.resume(VariantValue.of(EVENT_BOOT))
        }
        else machine.popSignal() match {
          case null => guest.resume(VariantValue.of(EVENT_WAKE))
          case signal => guest.resume(VariantValue.of(EVENT_SIGNAL, Array[AnyRef](signal.name, WasmValues.toTree(signal.args, handles))))
        }

      // A boot loader hands over to the module it loaded. That module boots
      // right away, still within the initialization run if this is one.
      var current = step
      var execs = 0
      while (current != null && current.index == STEP_EXEC) {
        execs += 1
        if (execs > WasmArchitecture.MaxExecs) {
          return new ExecutionResult.Error("exec: too many nested boot loaders")
        }
        val module = ModuleFormat.decode(current.payload.asInstanceOf[Array[Byte]], Settings.get.wasmMaxModuleSize)
        if (module == null) {
          return new ExecutionResult.Error("exec: not a WebAssembly module")
        }
        replaceGuest(module)
        current = guest.resume(VariantValue.of(EVENT_BOOT))
      }
      doneWithInitRun = true

      if (current == null) {
        // Suspended inside a host call that has to run on the server thread.
        if (pendingCall != null) new ExecutionResult.SynchronizedCall()
        else new ExecutionResult.Error("suspended without a pending call")
      }
      else toExecutionResult(current)
    }
    catch {
      case t: Throwable =>
        pendingCall = null
        flushOutput()
        WasiSupport.exitCode(t).toScala.map(_.intValue) match {
          case Some(0) => new ExecutionResult.Shutdown(false)
          case Some(code) => new ExecutionResult.Error(withStderr("exited with code " + code))
          case None => new ExecutionResult.Error(describeError(t))
        }
    }
    finally {
      budget.disarm()
    }
  }

  private def flushOutput(): Unit = {
    Option(stdout).foreach(_.flush())
    Option(stderr).foreach(_.flush())
  }

  /** Prefers what the guest last wrote to stderr (e.g. a panic message). */
  private def withStderr(fallback: String): String =
    Option(stderr).map(_.summary).filter(_.nonEmpty).getOrElse(fallback)

  private def toExecutionResult(step: VariantValue): ExecutionResult = {
    val result = step.index match {
      case STEP_SLEEP =>
        val ticks = step.payload.asInstanceOf[java.lang.Double].doubleValue * 20
        new ExecutionResult.Sleep(if (ticks.isNaN || ticks <= 0) 0 else if (ticks >= Int.MaxValue) Int.MaxValue else ticks.toInt)
      case STEP_WAIT =>
        new ExecutionResult.Sleep(Int.MaxValue)
      case STEP_SYNC =>
        if (pendingCall != null) return new ExecutionResult.SynchronizedCall()
        new ExecutionResult.Error("synchronized call requested, but no call is pending")
      case STEP_SHUTDOWN =>
        new ExecutionResult.Shutdown(step.payload.asInstanceOf[java.lang.Boolean].booleanValue)
      case STEP_CRASH =>
        new ExecutionResult.Error(Option(step.payload.asInstanceOf[String]).filter(_.nonEmpty).getOrElse("unknown error"))
      case _ =>
        new ExecutionResult.Error("invalid step")
    }
    // A pending call is only honoured if the guest asks for it right away.
    pendingCall = null
    result
  }

  private def describeError(t: Throwable): String = {
    val causes = Iterator.iterate(t)(_.getCause).takeWhile(_ != null).take(16).toSeq
    causes.collectFirst {
      case e: WasmTimeoutException => e.getMessage
      case e: WasmGuestException => e.getMessage
      case e: AbiException => "invalid host call: " + e.getMessage
      case _: StackOverflowError => "stack overflow"
      case e: TrapException => withStderr("trap: " + e.getMessage)
    }.getOrElse(t match {
      case e: ChicoryException =>
        "error: " + Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
      case _ =>
        OpenComputers.log.warn("Unexpected error in WebAssembly machine. This is a bug!", t)
        "kernel panic: this is a bug, check your log file and report it"
    })
  }

  // ----------------------------------------------------------------------- //
  // Persistence

  private final val StateVersion = 1

  private def blobName(kind: String) = machine.node.address + "_wasm_" + kind

  override def saveData(nbt: CompoundTag): Unit = saveData(null, nbt)

  override def saveData(holder: MutableDataComponentHolder, nbt: CompoundTag): Unit = {
    if (guest == null || !Settings.get.allowPersistence) return
    try {
      val snapshot = guest.snapshot()
      if (codeCompressed == null) {
        codeCompressed = Compression.deflate(code)
      }
      SaveHandler.scheduleSave(machine.host, nbt, blobName("module"), codeCompressed)
      SaveHandler.scheduleSave(machine.host, nbt, blobName("state"), snapshot.toBytes)

      nbt.putBoolean("initialized", doneWithInitRun)
      nbt.put("handles", saveHandles(holder))
      Option(pendingCall).foreach(call => nbt.put("pendingCall", PendingCall.save(call)))
      Option(syncResult).foreach(result => nbt.putByteArray("syncResult", WitValueCodec.encode(CALL_RESULT, result)))
      nbt.putInt("version", StateVersion)
    }
    catch {
      case t: Throwable =>
        OpenComputers.log.warn(s"Could not persist WebAssembly machine at ${machine.host.machinePosition}, it will reboot when loaded.", t)
        nbt.remove("version")
    }
  }

  override def loadData(nbt: CompoundTag): Unit = loadData(null, nbt)

  override def loadData(holder: DataComponentHolder, nbt: CompoundTag): Unit = {
    if (!machine.isRunning) return
    if (!Settings.get.allowPersistence || nbt.getInt("version") != StateVersion) {
      // Nothing usable was saved (e.g. persistence was disabled): reboot.
      machine.stop()
      machine.start()
      return
    }

    // Failures propagate to the machine, which reboots it.
    val module = Compression.inflate(SaveHandler.load(nbt, blobName("module")))
    val snapshot = GuestSnapshot.fromBytes(SaveHandler.load(nbt, blobName("state")))
    loadHandles(holder, nbt.getList("handles", Tag.TAG_COMPOUND))
    doneWithInitRun = nbt.getBoolean("initialized")
    pendingCall = if (nbt.contains("pendingCall")) PendingCall.load(nbt.getCompound("pendingCall")) else null
    syncResult = if (nbt.contains("syncResult")) WitValueCodec.decode(CALL_RESULT, nbt.getByteArray("syncResult")).asInstanceOf[VariantValue] else null
    guest = link(module, snapshot)
  }

  private def registries = ServerLifecycleHooks.getCurrentServer.registryAccess()

  private def saveHandles(holder: MutableDataComponentHolder): ListTag = {
    val list = new ListTag()
    for ((id, value) <- handles.entries) {
      val data = new CompoundTag()
      value.saveData(holder, data, registries)
      val entry = new CompoundTag()
      entry.putInt("id", id)
      entry.putString("class", value.getClass.getName)
      entry.put("data", data)
      list.add(entry)
    }
    list
  }

  private def loadHandles(holder: DataComponentHolder, list: ListTag): Unit = {
    for (i <- 0 until list.size) {
      val entry = list.getCompound(i)
      val value = Class.forName(entry.getString("class")).getDeclaredConstructor().newInstance().asInstanceOf[Value]
      value.loadData(holder, entry.getCompound("data"), registries)
      handles.restore(entry.getInt("id"), value)
    }
  }

  // ----------------------------------------------------------------------- //
  // Host calls

  private def option(value: AnyRef): VariantValue = if (value == null) VariantValue.none() else VariantValue.some(value)

  private def callError(kind: Int, message: String): VariantValue = VariantValue.of(CALL_RESULT_ERR, VariantValue.of(kind, message))

  private def ok(results: Array[AnyRef]): VariantValue = VariantValue.of(CALL_RESULT_OK, WasmValues.toTree(results, handles))

  private def complete(call: () => Array[AnyRef]): VariantValue = {
    try ok(call())
    catch {
      case t: Throwable => failure(t)
    }
  }

  /**
   * Schedules a call for the server thread. Guests that can suspend block in
   * the current import; others get `pending`.
   */
  private def schedule(call: PendingCall): AnyRef = {
    if (pendingCall != null) callError(CALL_ERROR_FAILED, "a synchronized call is already pending")
    else {
      pendingCall = call
      if (guest != null && guest.canSuspend) WasmGuest.SUSPEND
      else VariantValue.of(CALL_RESULT_PENDING)
    }
  }

  /**
   * Runs direct callbacks right away and schedules everything else for the
   * server thread, like the Lua kernel does.
   */
  private def invoke(direct: Boolean, call: () => Array[AnyRef], pending: => PendingCall): AnyRef = {
    if (direct) {
      try {
        return ok(call())
      }
      catch {
        case _: LimitReachedException => // Out of call budget, retry synchronized.
        case t: Throwable => return failure(t)
      }
    }
    schedule(pending)
  }

  /** Performs a scheduled call, on the server thread. */
  private def execute(call: PendingCall): VariantValue = call match {
    case PendingCall.OnComponent(address, method, tree) =>
      arguments(tree) match {
        case Left(error) => error
        case Right(params) => complete(() => machine.invoke(address, method, params))
      }
    case PendingCall.OnValue(id, method, tree) =>
      handles.get(id) match {
        case None => callError(CALL_ERROR_BAD_ARGUMENT, "invalid handle")
        case Some(value) => arguments(tree) match {
          case Left(error) => error
          case Right(params) => complete(() => machine.invoke(value, method, params))
        }
      }
  }

  private def failure(t: Throwable): VariantValue = {
    if (Settings.get.logLuaCallbackErrors && !t.isInstanceOf[LimitReachedException]) {
      OpenComputers.log.warn("Exception in WebAssembly host call.", t)
    }
    t match {
      case _: LimitReachedException => callError(CALL_ERROR_FAILED, "call budget exceeded")
      case e: IllegalArgumentException if e.getMessage != null => callError(CALL_ERROR_BAD_ARGUMENT, e.getMessage)
      case _: NoSuchMethodException => callError(CALL_ERROR_NO_SUCH_METHOD, null)
      case e if e.getMessage != null => callError(CALL_ERROR_FAILED, e.getMessage)
      case _: IndexOutOfBoundsException => callError(CALL_ERROR_BAD_ARGUMENT, "index out of bounds")
      case _: IllegalArgumentException => callError(CALL_ERROR_BAD_ARGUMENT, "bad argument")
      case _: FileNotFoundException => callError(CALL_ERROR_FAILED, "file not found")
      case _: SecurityException => callError(CALL_ERROR_FAILED, "access denied")
      case _: IOException => callError(CALL_ERROR_FAILED, "i/o error")
      case e =>
        OpenComputers.log.warn("Unexpected error in WebAssembly host call.", e)
        callError(CALL_ERROR_FAILED, "unknown error")
    }
  }

  private def visibleComponent(address: String): Option[Component] = {
    val self = node
    if (self == null || self.network == null) None
    else Option(self.network.node(address)) match {
      case Some(component: Component) if component.canBeSeenFrom(self) || component == self => Some(component)
      case _ => None
    }
  }

  private def methodInfos(methods: util.Map[String, Callback]): util.List[AnyRef] = {
    val infos = new util.ArrayList[AnyRef]()
    methods.asScala.toSeq.sortBy(_._1).foreach {
      case (name, callback) => infos.add(Array[AnyRef](
        name,
        Boolean.box(callback.direct),
        Boolean.box(callback.getter),
        Boolean.box(callback.setter),
        Int.box(callback.limit)))
    }
    infos
  }

  private def documentation(methods: util.Map[String, Callback], method: String): VariantValue =
    option(Option(methods.get(method)).map(_.doc).filter(doc => doc != null && doc.nonEmpty).orNull)

  private def arguments(tree: AnyRef): Either[VariantValue, Array[AnyRef]] = {
    try Right(WasmValues.fromTree(tree, handles))
    catch {
      case e: IllegalArgumentException => Left(callError(CALL_ERROR_BAD_ARGUMENT, e.getMessage))
    }
  }

  private def handle(argument: AnyRef): Int = argument.asInstanceOf[Integer].intValue

  private def withValue(argument: AnyRef)(f: Value => AnyRef): AnyRef = handles.get(handle(argument)) match {
    case Some(value) => f(value)
    case _ => callError(CALL_ERROR_BAD_ARGUMENT, "invalid handle")
  }

  private def dispose(value: Value): Unit = {
    try value.dispose(machine) catch {
      case t: Throwable => OpenComputers.log.warn("Error in dispose method of userdata of type " + value.getClass.getName, t)
    }
  }

  // ----------------------------------------------------------------------- //

  private def createBindings(): WasmGuest.Bindings = {
    val bindings = new WasmGuest.Bindings()

    // component

    bindings.bind(COMPONENT, COMPONENT_ENUMERATE, args => {
      val filter = Option(VariantValue.unwrapOption(args(0)).asInstanceOf[String])
      val exact = args(1).asInstanceOf[java.lang.Boolean].booleanValue
      val entries = new util.ArrayList[AnyRef]()
      components.synchronized {
        components.asScala.toSeq.sortBy(_._1).foreach {
          case (address, name) =>
            if (filter.forall(f => if (exact) name == f else name.contains(f))) {
              entries.add(Array[AnyRef](address, name))
            }
        }
      }
      entries
    })

    bindings.bind(COMPONENT, COMPONENT_KIND, args => {
      val address = args(0).asInstanceOf[String]
      option(components.synchronized(components.get(address)))
    })

    bindings.bind(COMPONENT, COMPONENT_SLOT, args => {
      val address = args(0).asInstanceOf[String]
      if (components.synchronized(components.containsKey(address))) VariantValue.some(Int.box(machine.host.componentSlot(address)))
      else VariantValue.none()
    })

    bindings.bind(COMPONENT, COMPONENT_METHODS, args => {
      option(visibleComponent(args(0).asInstanceOf[String]).map(component => methodInfos(machine.methods(component.host))).orNull)
    })

    bindings.bind(COMPONENT, COMPONENT_DOC, args => {
      visibleComponent(args(0).asInstanceOf[String]) match {
        case Some(component) => documentation(machine.methods(component.host), args(1).asInstanceOf[String])
        case _ => VariantValue.none()
      }
    })

    bindings.bind(COMPONENT, COMPONENT_INVOKE, args => {
      val address = args(0).asInstanceOf[String]
      val method = args(1).asInstanceOf[String]
      visibleComponent(address) match {
        case None => callError(CALL_ERROR_NO_SUCH_COMPONENT, null)
        case Some(component) => Option(machine.methods(component.host).get(method)) match {
          case None => callError(CALL_ERROR_NO_SUCH_METHOD, null)
          case Some(callback) => arguments(args(2)) match {
            case Left(error) => error
            case Right(params) => invoke(callback.direct,
              () => machine.invoke(address, method, params),
              PendingCall.OnComponent(address, method, args(2)))
          }
        }
      }
    })

    // userdata

    bindings.bind(USERDATA, USERDATA_METHODS, args => {
      option(handles.get(handle(args(0))).map(value => methodInfos(machine.methods(value))).orNull)
    })

    bindings.bind(USERDATA, USERDATA_DOC, args => {
      handles.get(handle(args(0))) match {
        case Some(value) => documentation(machine.methods(value), args(1).asInstanceOf[String])
        case _ => VariantValue.none()
      }
    })

    bindings.bind(USERDATA, USERDATA_INVOKE, args => withValue(args(0)) { value =>
      val method = args(1).asInstanceOf[String]
      Option(machine.methods(value).get(method)) match {
        case None => callError(CALL_ERROR_NO_SUCH_METHOD, null)
        case Some(callback) => arguments(args(2)) match {
          case Left(error) => error
          case Right(params) => invoke(callback.direct,
            () => machine.invoke(value, method, params),
            PendingCall.OnValue(handle(args(0)), method, args(2)))
        }
      }
    })

    bindings.bind(USERDATA, USERDATA_APPLY, args => withValue(args(0)) { value =>
      arguments(args(1)) match {
        case Left(error) => error
        case Right(params) => complete(() => Registry.convert(Array[AnyRef](value.apply(machine, new ArgumentsImpl(params.toSeq)))))
      }
    })

    bindings.bind(USERDATA, USERDATA_UNAPPLY, args => withValue(args(0)) { value =>
      arguments(args(1)) match {
        case Left(error) => error
        case Right(params) => complete(() => {
          value.unapply(machine, new ArgumentsImpl(params.toSeq))
          Array.empty[AnyRef]
        })
      }
    })

    bindings.bind(USERDATA, USERDATA_CALL, args => withValue(args(0)) { value =>
      arguments(args(1)) match {
        case Left(error) => error
        case Right(params) => complete(() => Registry.convert(value.call(machine, new ArgumentsImpl(params.toSeq))))
      }
    })

    bindings.bind(USERDATA, USERDATA_RELEASE, args => {
      handles.release(handle(args(0))).foreach(dispose)
      null
    })

    // computer

    bindings.bind(COMPUTER, COMPUTER_ADDRESS, _ => Option(node).map(_.address).getOrElse(""))

    bindings.bind(COMPUTER, COMPUTER_TMP_ADDRESS, _ => option(machine.tmpAddress))

    bindings.bind(COMPUTER, COMPUTER_TOTAL_MEMORY, _ => Long.box(currentMaxPages.toLong * WasmGuest.PAGE_SIZE))

    bindings.bind(COMPUTER, COMPUTER_FREE_MEMORY, _ => {
      val used = Option(guest).map(_.pages).getOrElse(0)
      Long.box(((currentMaxPages - used) max 0).toLong * WasmGuest.PAGE_SIZE)
    })

    bindings.bind(COMPUTER, COMPUTER_UPTIME, _ => Double.box(machine.upTime))

    bindings.bind(COMPUTER, COMPUTER_CPU_TIME, _ => Double.box(machine.cpuTime))

    bindings.bind(COMPUTER, COMPUTER_REAL_TIME, _ => Double.box(System.currentTimeMillis / 1000.0))

    bindings.bind(COMPUTER, COMPUTER_WORLD_TIME, _ => Long.box(machine.worldTime))

    bindings.bind(COMPUTER, COMPUTER_ENERGY, _ => node match {
      case _ if Settings.get.ignorePower => Double.box(Double.PositiveInfinity)
      case connector: Connector => Double.box(connector.globalBuffer)
      case _ => Double.box(0)
    })

    bindings.bind(COMPUTER, COMPUTER_MAX_ENERGY, _ => node match {
      case connector: Connector => Double.box(connector.globalBufferSize)
      case _ => Double.box(0)
    })

    bindings.bind(COMPUTER, COMPUTER_USERS, _ => util.List.of[AnyRef](machine.users.toSeq: _*))

    bindings.bind(COMPUTER, COMPUTER_ADD_USER, args => {
      try {
        machine.addUser(args(0).asInstanceOf[String])
        VariantValue.ok(null)
      }
      catch {
        case t: Throwable => VariantValue.err(Option(t.getMessage).getOrElse("unknown error"))
      }
    })

    bindings.bind(COMPUTER, COMPUTER_REMOVE_USER, args => Boolean.box(machine.removeUser(args(0).asInstanceOf[String])))

    bindings.bind(COMPUTER, COMPUTER_PUSH_SIGNAL, args => {
      arguments(args(1)) match {
        case Right(params) => Boolean.box(machine.signal(args(0).asInstanceOf[String], params: _*))
        case Left(_) => Boolean.box(false)
      }
    })

    bindings.bind(COMPUTER, COMPUTER_ARCHITECTURE, _ => api.Machine.getArchitectureName(classOf[WasmArchitecture]))

    // unicode

    bindings.bind(UNICODE, UNICODE_CHAR_WIDTH, args => Int.box(FontUtils.wcwidth(args(0).asInstanceOf[Integer].intValue) max 0 min 255))

    bindings.bind(UNICODE, UNICODE_STRING_WIDTH, args => {
      Int.box(args(0).asInstanceOf[String].codePoints.map(c => FontUtils.wcwidth(c) max 0).sum)
    })

    bindings.bind(UNICODE, UNICODE_TRUNCATE_TO_WIDTH, args => {
      FontUtils.wtrunc(args(0).asInstanceOf[String], Integer.toUnsignedLong(args(1).asInstanceOf[Integer].intValue))
    })

    bindings
  }

  /** Memory limit of the running guest, or of the next boot if not running. */
  private def currentMaxPages: Int = Option(guest).map(_.maxPages).getOrElse(maxPages)
}

object WasmArchitecture {
  /** How many modules may hand over to the next one in a single run. */
  final val MaxExecs = 8
}

