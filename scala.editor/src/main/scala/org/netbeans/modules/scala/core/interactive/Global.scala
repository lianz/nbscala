package org.netbeans.modules.scala.core.interactive
import scala.tools.nsc._

import java.io.{ PrintWriter, StringWriter }

import java.util.logging.Logger
import scala.collection.mutable.{LinkedHashMap, SynchronizedMap}
import scala.concurrent.SyncVar
import scala.util.control.ControlThrowable
import scala.tools.nsc.io.AbstractFile
import scala.tools.nsc.util.{SourceFile, Position, RangePosition, OffsetPosition, NoPosition}
import scala.tools.nsc.reporters._
import scala.tools.nsc.symtab._
import scala.tools.nsc.ast._

// ======= Modifed by Caoyuan

/** The main class of the presentation compiler in an interactive environment such as an IDE
 */
class Global(_settings: Settings, _reporter: Reporter)
extends scala.tools.nsc.Global(_settings, _reporter)
   with CompilerControl
   with RangePositions
   with ContextTrees
   with RichCompilationUnits {
  self =>

  import definitions._

  final val debugIDE = false

  protected val GlobalLog = Logger.getLogger(this.getClass.getName)

  override def onlyPresentation = true

  /** A list indicating in which order some units should be typechecked.
   *  All units in firsts are typechecked before any unit not in this list
   *  Modified by askToDoFirst, reload, typeAtTree.
   */
  var firsts: List[SourceFile] = List()

  /** A map of all loaded files to the rich compilation units that correspond to them.
   */ 
  val unitOfFile = new LinkedHashMap[AbstractFile, RichCompilationUnit] with
  SynchronizedMap[AbstractFile, RichCompilationUnit]

  /** The currently active typer run */
  var currentTyperRun: TyperRun = _

  /** Is a background compiler run needed? */
  private var outOfDate = false
  var currentAction: () => Unit = _

  /** Units compiled by a run with id >= minRunId are considered up-to-date  */
  private[interactive] var minRunId = 1

  /** Is a reload/background compiler currently running? */
  private var acting = false

  // ----------- Overriding hooks in nsc.Global -----------------------
  
  /** Called from typechecker, which signal hereby that a node has been completely typechecked.
   *  If the node is included in unit.targetPos, abandons run and returns newly attributed tree.
   *  Otherwise, if there's some higher priority work to be done, also abandons run with a FreshRunReq.
   *  @param  context  The context that typechecked the node
   *  @param  old      The original node
   *  @param  result   The transformed node
   */
  override def signalDone(context: Context, old: Tree, result: Tree) {
    def integrateNew() {
      context.unit.body = new TreeReplacer(old, result) transform context.unit.body
    }
    // per scala's complier code changes during 2010, Symbols#activeLocks seems do not equals 0 anymore
    //if (activeLocks == 0) {
    if (context.unit != null &&
        result.pos.isOpaqueRange &&
        (result.pos includes context.unit.targetPos)) {
      integrateNew()
      var located = new Locator(context.unit.targetPos) locateIn result
      if (located == EmptyTree) {
        println("something's wrong: no "+context.unit+" in "+result+result.pos)
        located = result
      }
      throw new TyperResult(located)
    }
    val typerRun = currentTyperRun

    while(true)
      try {
        pollForWork()
        if (typerRun == currentTyperRun)
          return

        // @Martin
        // Guard against NPEs in integrateNew if context.unit == null here.
        // But why are we doing this at all? If it was non-null previously
        // integrateNew will already have been called. If it was null previously
        // it will still be null now?
        if (context.unit != null)
          integrateNew()
        throw FreshRunReq
      } catch {
        case ex : ValidateException => // Ignore, this will have been reported elsewhere
        case t : Throwable => throw t
      }
    //}
  }

  /** Called from typechecker every time a context is created.
   *  Registers the context in a context tree
   */
  override def registerContext(c: Context) = c.unit match {
    case u: RichCompilationUnit => addContext(u.contexts, c)
    case _ =>
  }

  // ----------------- Polling ---------------------------------------

  /** Called from runner thread and signalDone:
   *  Poll for exeptions. 
   *  Poll for work reload/typedTreeAt/doFirst commands during background checking.
   */
  def pollForWork() {
    scheduler.pollThrowable() match {
      case Some(ex @ CancelActionReq) => if (acting) throw ex
      case Some(ex @ FreshRunReq) =>
        currentTyperRun = newTyperRun
        minRunId = currentRunId
        if (outOfDate) throw ex
        else outOfDate = true
      case Some(ex: Throwable) => throw ex
      case _ =>
    }
    scheduler.nextWorkItem() match {
      case Some(action) =>
        try {
          acting = true
          currentAction = action
          if (debugIDE) println("picked up work item: "+action)
          action()
          if (debugIDE) println("done with work item: "+action)
        } catch {
          case CancelActionReq =>
            if (debugIDE) println("cancelled work item: "+action)
        } finally {
          if (debugIDE) println("quitting work item: "+action)
          acting = false
          currentAction = null
        }
      case None =>
    }
  }    

  def debugInfo(source : SourceFile, start : Int, length : Int): String = {
    println("DEBUG INFO "+source+"/"+start+"/"+length)
    val end = start+length
    val pos = rangePos(source, start, start, end)

    val tree = locateTree(pos)
    val sw = new StringWriter
    val pw = new PrintWriter(sw)
    newTreePrinter(pw).print(tree)
    pw.flush
    
    val typed = new Response[Tree]
    askTypeAt(pos, false, typed)
    val typ = typed.get.left.toOption match {
      case Some(tree) =>
        val sw = new StringWriter
        val pw = new PrintWriter(sw)
        newTreePrinter(pw).print(tree)
        pw.flush
        sw.toString
      case None => "<None>"      
    }

    val completionResponse = new Response[List[Member]]
    askTypeCompletion(pos, false, completionResponse)
    val completion = completionResponse.get.left.toOption match {
      case Some(members) =>
        members mkString "\n"
      case None => "<None>"      
    }
    
    source.content.view.drop(start).take(length).mkString+" : "+source.path+" ("+start+", "+end+
    ")\n\nlocateTree:\n"+sw.toString+"\n\naskTypeAt:\n"+typ+"\n\ncompletion:\n"+completion
  }

  // ----------------- The Background Runner Thread -----------------------

  /** The current presentation compiler runner */
  private var compileRunner = newRunnerThread

  /** Create a new presentation compiler runner.
   */
  def newRunnerThread: Thread = new Thread("Scala Presentation Compiler") {
    override def run() {
      try {
        while (true) {
          scheduler.waitForMoreWork()
          pollForWork()
          while (outOfDate) {
            try {
              backgroundCompile()
              outOfDate = false
            } catch {
              case FreshRunReq =>
            }
          }
        }
      } catch {
        case ex: InterruptedException =>
          Thread.currentThread.interrupt // interrupt again to avoid posible out-loop issue
        case ShutdownReq =>
          GlobalLog.info("ShutdownReq processed")
          Thread.currentThread.interrupt
        case ex => 
          GlobalLog.info(ex.getClass.getSimpleName + " processed, will start a newRunnerThread: " + ex.getMessage)
          outOfDate = false
          compileRunner = newRunnerThread
          ex match {
            case FreshRunReq =>   // This shouldn't be reported
            case _ : ValidateException => // This will have been reported elsewhere
            case _ => ex.printStackTrace(); inform("Fatal Error: "+ex)
          }
      }
    }
    start()
  }

  /** Compile all given units
   */ 
  private def backgroundCompile() {
    if (debugIDE) inform("Starting new presentation compiler type checking pass")
    reporter.reset
    firsts = firsts filter (s => unitOfFile contains (s.file))
    val prefix = firsts map unitOf
    val units = prefix ::: (unitOfFile.valuesIterator.toList diff prefix) filter (!_.isUpToDate)
    recompile(units)
    if (debugIDE) inform("Everything is now up to date")
  }

  /** Reset unit to just-parsed state */
  def reset(unit: RichCompilationUnit): Unit =
    if (unit.status > JustParsed) {
      unit.depends.clear()
      unit.defined.clear()
      unit.synthetics.clear()
      unit.toCheck.clear()
      unit.targetPos = NoPosition
      unit.contexts.clear()
      unit.body = EmptyTree
      unit.status = NotLoaded
    }

  /** Parse unit and create a name index. */
  def parse(unit: RichCompilationUnit): Unit = {
    val start = System.currentTimeMillis
    currentTyperRun.compileLate(unit)
    if (!reporter.hasErrors) validatePositions(unit.body)
    GlobalLog.info("Parse took " + (System.currentTimeMillis - start) + "ms")
    //println("parsed: [["+unit.body+"]]")
    unit.status = JustParsed
  }

  /** Make sure symbol and type attributes are reset and recompile units. 
   */
  def recompile(units: List[RichCompilationUnit]) {
    for (unit <- units) {
      reset(unit)
      if (debugIDE) inform("parsing: "+unit)
      parse(unit)
    }
    for (unit <- units) {
      if (debugIDE) inform("type checking: "+unit)
      activeLocks = 0
      currentTyperRun.typeCheck(unit)
      unit.status = currentRunId
    }
  }

  /** Move list of files to front of firsts */
  def moveToFront(fs: List[SourceFile]) {
    firsts = fs ::: (firsts diff fs)
  }

  // ----------------- Implementations of client commmands -----------------------
  
  def respond[T](result: Response[T])(op: => T): Unit =
    try {
      result set Left(op)
      return
    } catch {
      case ex @ FreshRunReq =>
 	scheduler.postWorkItem(() => respond(result)(op))
 	throw ex
      case ex =>
 	result set Right(ex)
 	throw ex
    }

  /** Make sure a set of compilation units is loaded and parsed */
  def reloadSources(sources: List[SourceFile]) {
    currentTyperRun = newTyperRun
    for (source <- sources) {
      val unit = new RichCompilationUnit(source)
      unitOfFile(source.file) = unit
      parse(unit)
    }
    moveToFront(sources)
  }

  /** Make sure a set of compilation units is loaded and parsed */
  def reload(sources: List[SourceFile], result: Response[Unit]) {
    respond(result)(reloadSources(sources))
    if (outOfDate) throw FreshRunReq
    else outOfDate = true
  }

  /** A fully attributed tree located at position `pos`  */
  def typedTreeAt(pos: Position, forceReload: Boolean): Tree = {
    val unit = unitOf(pos)
    val sources = List(pos.source)
    if (unit.status == NotLoaded || forceReload) reloadSources(sources)
    moveToFront(sources)
    val typedTree = currentTyperRun.typedTreeAt(pos)
    new Locator(pos) locateIn typedTree
  }

  /** A fully attributed tree corresponding to the entire compilation unit  */
  def typedTree(source: SourceFile, forceReload: Boolean): Tree = {
    val unit = unitOf(source)
    val sources = List(source)
    if (unit.status == NotLoaded || forceReload) reloadSources(sources)
    moveToFront(sources)
    currentTyperRun.typedTree(unitOf(source))
  }

  /** Set sync var `result` to a fully attributed tree located at position `pos`  */
  def getTypedTreeAt(pos: Position, forceReload: Boolean, result: Response[Tree]) {
    respond(result)(typedTreeAt(pos, forceReload))
  }

  /** Set sync var `result` to a fully attributed tree corresponding to the entire compilation unit  */
  def getTypedTree(source : SourceFile, forceReload: Boolean, result: Response[Tree]) {
    respond(result)(typedTree(source, forceReload))
  }

  def stabilizedType(tree: Tree): Type = tree match {
    case Ident(_) if tree.symbol.isStable => singleType(NoPrefix, tree.symbol)
    case Select(qual, _) if  qual.tpe != null && tree.symbol.isStable => singleType(qual.tpe, tree.symbol)
    case Import(expr, selectors) =>
      tree.symbol.info match {
        case analyzer.ImportType(expr) => expr match {
            case s@Select(qual, name) => singleType(qual.tpe, s.symbol)
            case i : Ident => i.tpe
            case _ => tree.tpe
          }
        case _ => tree.tpe
      }

    case _ => tree.tpe
  }

  import analyzer.{SearchResult, ImplicitSearch}

  def getScopeCompletion(pos: Position, forceReload: Boolean, result: Response[List[Member]]) {
    respond(result) { scopeMembers(pos, forceReload) }
  }

  val Dollar = newTermName("$")

  /** Return all members visible without prefix in context enclosing `pos`. */
  def scopeMembers(pos: Position, forceReload: Boolean): List[ScopeMember] = {
    typedTreeAt(pos, forceReload) // to make sure context is entered
    val context = try {
      doLocateContext(pos)
    } catch {case ex => ex.printStackTrace; NoContext}

    val locals = new LinkedHashMap[Name, ScopeMember]
    def addScopeMember(sym: Symbol, pre: Type, viaImport: Tree) =
      if (!sym.name.decode.containsName(Dollar) &&
          !sym.hasFlag(Flags.SYNTHETIC) &&
          !locals.contains(sym.name)) {
        //println("adding scope member: "+pre+" "+sym)
        val tpe = try {
          pre.memberType(sym)
        } catch {case ex => ex.printStackTrace; NoPrefix}

        locals(sym.name) = new ScopeMember(
          sym,
          tpe,
          context.isAccessible(sym, pre, false),
          viaImport)
      }
    var cx = context
    while (cx != NoContext) {
      for (sym <- cx.scope)
        addScopeMember(sym, NoPrefix, EmptyTree)

      cx = cx.enclMethod
      if (cx != NoContext) {
        for (sym <- cx.scope)
          addScopeMember(sym, NoPrefix, EmptyTree)
      }

      cx = cx.enclClass
      val pre = cx.prefix
      for (sym <- pre.members)
        addScopeMember(sym, pre, EmptyTree)
      cx = cx.outer
    }
    for (imp <- context.imports) {
      val pre = imp.qual.tpe
      for (sym <- imp.allImportedSymbols) {
        addScopeMember(sym, pre, imp.qual)
      }
    }
    val result = locals.valuesIterator.toList
    if (debugIDE) for (m <- result) println(m)
    result
  }

  def getTypeCompletion(pos: Position, forceReload: Boolean, result: Response[List[Member]]) {
    respond(result) { typeMembers(pos, forceReload) }
    if (debugIDE) scopeMembers(pos, forceReload)
  }

  def typeMembers(pos: Position, forceReload: Boolean): List[TypeMember] = {
    var tree = typedTreeAt(pos, forceReload)
    tree match {
      case tt : TypeTree => tree = tt.original
      case _ =>
    }

    tree match {
      case Select(qual, name) if tree.tpe == ErrorType => tree = qual
      case _ =>
    }

    val context = try {
      doLocateContext(pos)
    } catch {case ex => println(ex.getMessage); NoContext}

    if (tree.tpe == null)
      tree = analyzer.newTyper(context).typedQualifier(tree)

    val tpe = tree.tpe match {
      case x@(null | ErrorType | NoType) =>
        GlobalLog.warning("try to recover type of tree: " + tree)
        recoveredType(tree) match {
          case Some(x) => x.resultType
          case None =>
            GlobalLog.warning("Tree type is null or error: tree=" + tree + ", tpe=" + x + ", but we have qualToRecoveredType=" + qualToRecoveredType)
            return Nil
        }
      case x => x.resultType
    }


    val superAccess = tree.isInstanceOf[Super]
    val scope = new Scope
    val members = new LinkedHashMap[Symbol, TypeMember]
    def addTypeMember(sym: Symbol, pre: Type, inherited: Boolean, viaView: Symbol) {
      val symtpe = pre.memberType(sym)
      if (scope.lookupAll(sym.name) forall (sym => !(members(sym).tpe matches symtpe))) {
        scope enter sym
        members(sym) = new TypeMember(
          sym,
          symtpe,
          context.isAccessible(sym, pre, superAccess && (viaView == NoSymbol)),
          inherited,
          viaView)
      }
    }

    def addPackageMember(sym: Symbol, pre: Type, inherited: Boolean, viaView: Symbol) {
      // * don't ask symtpe here via pre.memberType(sym) or sym.tpe, which may throw "no-symbol does not have owner" in doComplete
      members(sym) = new TypeMember(
        sym,
        NoPrefix,
        context.isAccessible(sym, pre, false),
        inherited,
        viaView)
    }

    def viewApply(view: SearchResult): Tree = {
      assert(view.tree != EmptyTree)
      try {
        analyzer.newTyper(context.makeImplicit(false)).typed(Apply(view.tree, List(tree)) setPos tree.pos)
      } catch {
        case ex: TypeError => EmptyTree
      }
    }

    // ----- begin adding members
    val pre = try {
      stabilizedType(tree) match {
        case null => tpe
        case x => x
      }
    } catch {case ex => println(ex.getMessage); tpe}

    val ownerTpe = tpe match {
      case analyzer.ImportType(expr) => pre
      case _ => tpe
    }

    val isPackage = ownerTpe.typeSymbol hasFlag Flags.PACKAGE
    if (isPackage) {
      GlobalLog.info("Get typeMembers of package=" + ownerTpe)
      try {
        for (sym <- ownerTpe.members if !sym.isError && sym.nameString.indexOf('$') == -1) {
          addPackageMember(sym, pre, false, NoSymbol)
        }
      } catch {case ex => ex.printStackTrace}
    } else {
      GlobalLog.info("Get typeMembers at tree.class=" + tree.getClass.getSimpleName + ", tree.tpe=" + tree.tpe + ", tpe=" + tpe + ", pre=" + pre)
      try {
        for (sym <- ownerTpe.decls) {
          addTypeMember(sym, pre, false, NoSymbol)
        }
      } catch {case ex => ex.printStackTrace}

      try {
        for (sym <- ownerTpe.members) {
          addTypeMember(sym, pre, true, NoSymbol)
        }
      } catch {case ex => ex.printStackTrace}

      try {
        val applicableViews: List[SearchResult] =
          new ImplicitSearch(tree, definitions.functionType(List(ownerTpe), definitions.AnyClass.tpe), true, context.makeImplicit(false)).allImplicits

        for (view <- applicableViews) {
          val vtree = viewApply(view)
          val vpre = stabilizedType(vtree)
          for (sym <- vtree.tpe.members) {
            addTypeMember(sym, vpre, false, view.tree.symbol)
          }
        }
      } catch {case ex => ex.printStackTrace}

    }

    members.valuesIterator.toList
  }

  final def recoveredType(tree: Tree): Option[Type] = {
    def findViaGet(atree: Tree) = qualToRecoveredType.get(atree) match {
      case None => qualToRecoveredType find {
          case (Select(qual, _), _) => qual == atree
          case (SelectFromTypeTree(qual, _), _) => qual == atree
          case (Apply(fun, _), _) => fun == atree
          case (x, _) => x == atree // usaully Ident tree
        } match {
          case None => None
          case Some((_, tpe)) => Some(tpe)
        }
      case some => some
    }
    
    def findViaPos(atree: Tree) = qualToRecoveredType find {
      case (x@Select(qual, _), _) =>
        (x.pos sameRange atree.pos) || (qual.pos sameRange atree.pos)
      case (x@SelectFromTypeTree(qual, _), _) =>
        (x.pos sameRange atree.pos) || (qual.pos sameRange atree.pos)
      case (x@Apply(fun, _), _) =>
        (x.pos sameRange atree.pos) || (fun.pos sameRange atree.pos)
      case (x, _) =>
        (x.pos sameRange atree.pos) // usaully Ident tree
    } match {
      case None => None
      case Some((_, tpe)) => Some(tpe)
    }

    def find(op: Tree => Option[Type]) = {
      op(tree) match {
        case None =>
          tree match {
            case Select(qual, _) => op(qual)
            case SelectFromTypeTree(qual, _) => op(qual)
            case Apply(fun, _) => op(fun)
            case _ => None
          }
        case some => some
      }
    }

    find(findViaGet) match {
      case None => find(findViaPos)
      case some => some
    }
  }

  // ---------------- Helper classes ---------------------------

  /** A transformer that replaces tree `from` with tree `to` in a given tree */
  class TreeReplacer(from: Tree, to: Tree) extends Transformer {
    override def transform(t: Tree): Tree = {
      if (t == from) to
      else if ((t.pos includes from.pos) || t.pos.isTransparent) super.transform(t)
      else t
    }
  }

  /** The typer run */
  class TyperRun extends Run {
    // units is always empty

    /** canRedefine is used to detect double declarations in multiple source files.
     *  Since the IDE rechecks units several times in the same run, these tests
     *  are disabled by always returning true here.
     */
    override def canRedefine(sym: Symbol) = true

    def typeCheck(unit: CompilationUnit): Unit = {
      applyPhase(typerPhase, unit)
    }

    def enterNames(unit: CompilationUnit): Unit = {
      applyPhase(namerPhase, unit)
    }

    /** Return fully attributed tree at given position
     *  (i.e. largest tree that's contained by position)
     */
    def typedTreeAt(pos: Position): Tree = {
      println("starting typedTreeAt")
      val tree = locateTree(pos)
      println("at pos "+pos+" was found: "+tree+tree.pos.show)
      if (stabilizedType(tree) ne null) {
        println("already attributed")
        tree
      } else {
        val unit = unitOf(pos)
        assert(unit.status >= JustParsed)
        unit.targetPos = pos
        try {
          println("starting targeted type check")
          typeCheck(unit)
          throw new FatalError("tree not found")
        } catch {
          case ex: TyperResult =>
            ex.tree
        } finally {
          unit.targetPos = NoPosition
        }
      }
    }

    def typedTree(unit: RichCompilationUnit): Tree = {
      assert(unit.status >= JustParsed)
      unit.targetPos = NoPosition
      val start = System.currentTimeMillis
      typeCheck(unit)
      GlobalLog.info("Typer took " + (System.currentTimeMillis - start) + "ms")
      unit.body
    }

    /** Apply a phase to a compilation unit
     *  @return true iff typechecked correctly
     */
    private def applyPhase(phase: Phase, unit: CompilationUnit) {
      val oldSource = reporter.getSource
      reporter.withSource(unit.source) {
        atPhase(phase) { phase.asInstanceOf[GlobalPhase] applyPhase unit }
      }
    }
  }

  def newTyperRun = new TyperRun

  class TyperResult(val tree: Tree) extends ControlThrowable

  assert(globalPhase.id == 0)
}
